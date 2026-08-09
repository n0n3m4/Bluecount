package com.bluecount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceTest {
  private val alice = testSigner()
  private val bob = testSigner()
  private val carol = testSigner()
  private val everyone = listOf(alice, bob, carol).map { it.id }

  private fun trip(): TestLog =
    TestLog().apply {
      add(alice, Genesis("Ski trip", "EUR"))
      add(alice, Profile("Alice"))
      add(bob, Profile("Bob"))
      add(carol, Profile("Carol"))
    }

  @Test
  fun `balances always sum to zero and settle up clears them`() {
    val log = trip()
    log.add(alice) { id -> Put(id, title = "Hotel", cents = 30_000, payer = alice.id, shares = everyone.associateWith { 1L }) }
    log.add(bob) { id -> Put(id, title = "Petrol", cents = 5_001, payer = bob.id, shares = everyone.associateWith { 1L }) }
    log.add(carol) { id ->
      Put(id, title = "Dinner", cents = 9_000, payer = carol.id, mode = SplitMode.SHARES,
        shares = mapOf(alice.id to 2L, bob.id to 1L))
    }

    val state = fold(log.ops)
    val eur = state.balances.getValue("EUR")
    assertEquals(0L, eur.values.sum())
    assertEquals(3, state.expenses.size)
    assertEquals("Ski trip", state.name)
    assertEquals("Alice", state.nick(alice.id))

    val transfers = settleUp(eur)
    assertTrue("${transfers.size} transfers", transfers.size <= everyone.size - 1)
    val after = eur.toMutableMap()
    for (t in transfers) {
      after[t.from] = after.getValue(t.from) + t.cents
      after[t.to] = after.getValue(t.to) - t.cents
    }
    assertTrue("not settled: $after", after.values.all { it == 0L })
  }

  @Test
  fun `anyone can edit an expense and the last write in total order wins`() {
    val log = trip()
    val created = log.add(alice) { id -> Put(id, title = "Hotel", cents = 30_000, payer = alice.id, shares = everyone.associateWith { 1L }) }
    val id = created.key
    log.add(bob) { Put(id, title = "Hotel (fixed)", cents = 20_000, payer = alice.id, shares = everyone.associateWith { 1L }) }

    val state = fold(log.ops)
    assertEquals(1, state.expenses.size)
    assertEquals("Hotel (fixed)", state.expenses[0].title)
    assertEquals(20_000L, state.expenses[0].cents)
    assertEquals(bob.id, state.expenses[0].lastEditor)
  }

  @Test
  fun `delete is an op, not an erasure, and removes the expense from balances`() {
    val log = trip()
    val id = log.add(alice) { id -> Put(id, title = "Oops", cents = 1_234, payer = alice.id, shares = everyone.associateWith { 1L }) }.key
    log.add(carol) { Put(id, deleted = true) }

    val state = fold(log.ops)
    assertTrue(state.expenses.isEmpty())
    // Nothing left to owe in any currency at all, so there is not even a currency key.
    assertTrue(state.balances.isEmpty())
    // The delete is still in the log; nothing was rewritten.
    assertEquals(6, log.ops.size)
  }

  @Test
  fun `reimbursement moves the balance back towards zero`() {
    val log = trip()
    log.add(alice) { id -> Put(id, title = "Taxi", cents = 1_000, payer = alice.id, shares = mapOf(alice.id to 1L, bob.id to 1L)) }
    assertEquals(500L, fold(log.ops).balances.getValue("EUR")[alice.id])

    log.add(bob) { id ->
      Put(id, title = "Paid back", cents = 500, payer = bob.id, kind = Kind.REIMBURSEMENT, shares = mapOf(alice.id to 1L))
    }
    assertTrue(fold(log.ops).balances.getValue("EUR").values.all { it == 0L })
  }

  @Test
  fun `expenses list newest first by instant, and an op with no time sorts as its own midnight`() {
    val day = 20_000L
    val noon = day * 86_400_000L + 12 * 3_600_000L
    val log = trip()
    // Two on the same day an hour apart, plus one written before expenses carried a time at all.
    log.add(alice) { id -> Put(id, title = "Lunch", cents = 100, date = day, at = noon, payer = alice.id) }
    log.add(bob) { id -> Put(id, title = "Coffee", cents = 100, date = day, at = noon + 3_600_000L, payer = bob.id) }
    log.add(carol) { id -> Put(id, title = "Old", cents = 100, date = day, payer = carol.id) }

    assertEquals(listOf("Coffee", "Lunch", "Old"), fold(log.ops).expenses.map { it.title })
  }

  @Test
  fun `state does not depend on the order ops arrived in`() {
    val log = trip()
    log.add(alice) { id -> Put(id, title = "Hotel", cents = 30_000, payer = alice.id, shares = everyone.associateWith { 1L }) }
    val id = log.ops.last().key
    log.add(bob) { Put(id, title = "Hotel (fixed)", cents = 20_000, payer = alice.id, shares = everyone.associateWith { 1L }) }
    log.add(carol) { cid -> Put(cid, title = "Beers", cents = 777, payer = carol.id, shares = everyone.associateWith { 1L }) }

    assertEquals(fold(log.ops), fold(log.ops.shuffled()))
    assertEquals(fold(log.ops), fold(log.ops.reversed()))
  }
}
