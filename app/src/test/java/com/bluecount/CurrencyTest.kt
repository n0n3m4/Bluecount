package com.bluecount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule under test throughout: currencies never mix. Every balance map stands on its own and sums
 * to zero on its own, and the only thing that ever moves value between two of them is an explicit
 * [Kind.CONVERSION] op — which is itself net zero in both, so the invariant holds after it too.
 */
class CurrencyTest {
  private val alice = testSigner()
  private val bob = testSigner()
  private val carol = testSigner()
  private val everyone = listOf(alice, bob, carol).map { it.id }

  private fun trip(): TestLog =
    TestLog().apply {
      add(alice, Genesis("Silk road", "USD"))
      add(alice, Profile("Alice"))
      add(bob, Profile("Bob"))
      add(carol, Profile("Carol"))
    }

  private fun assertEachSumsToZero(state: EventState) {
    for ((cur, b) in state.balances) assertEquals("$cur does not sum to zero: $b", 0L, b.values.sum())
  }

  @Test
  fun `two currencies are counted independently and neither contaminates the other`() {
    val log = trip()
    log.add(alice) { id ->
      Put(id, title = "Hotel", cents = 30_000, payer = alice.id, currency = "USD",
        shares = everyone.associateWith { 1L })
    }
    log.add(bob) { id ->
      Put(id, title = "Taxi", cents = 900_000, payer = bob.id, currency = "KZT",
        shares = everyone.associateWith { 1L })
    }

    val state = fold(log.ops)
    assertEquals(setOf("USD", "KZT"), state.balances.keys)
    assertEachSumsToZero(state)

    // Alice paid the hotel and owes a third of the taxi; nothing about the taxi touches her USD row.
    assertEquals(20_000L, state.balances.getValue("USD").getValue(alice.id))
    assertEquals(-300_000L, state.balances.getValue("KZT").getValue(alice.id))
    assertEquals(600_000L, state.balances.getValue("KZT").getValue(bob.id))
    // Carol paid nothing, so she is a pure debtor in both, and by different amounts.
    assertEquals(-10_000L, state.balances.getValue("USD").getValue(carol.id))
    assertEquals(-300_000L, state.balances.getValue("KZT").getValue(carol.id))
  }

  @Test
  fun `a blank currency means the event default and an explicit one overrides it`() {
    val log = trip()
    // Ops written before per-expense currencies existed look exactly like this one.
    log.add(alice) { id -> Put(id, cents = 1_000, payer = alice.id, shares = mapOf(bob.id to 1L)) }
    log.add(bob) { id -> Put(id, cents = 1_000, payer = bob.id, currency = "EGP", shares = mapOf(alice.id to 1L)) }

    val state = fold(log.ops)
    assertEquals(setOf("USD", "EGP"), state.balances.keys)
    assertEquals("USD", state.expenses.single { it.currency == "USD" }.currency)
    assertEachSumsToZero(state)
  }

  @Test
  fun `the event default is listed first, remaining currencies alphabetically`() {
    val log = trip()
    for (cur in listOf("RUB", "EGP", "USD", "KZT")) {
      log.add(alice) { id ->
        Put(id, cents = 100, payer = alice.id, currency = cur, shares = mapOf(bob.id to 1L))
      }
    }
    assertEquals(listOf("USD", "EGP", "KZT", "RUB"), fold(log.ops).balances.keys.toList())
  }

  @Test
  fun `a partial payback reduces one debt in one currency and leaves the rest alone`() {
    val log = trip()
    log.add(alice) { id ->
      Put(id, title = "Hotel", cents = 30_000, payer = alice.id, currency = "USD",
        shares = mapOf(alice.id to 1L, bob.id to 1L))
    }
    log.add(bob) { id ->
      Put(id, title = "Taxi", cents = 900_000, payer = bob.id, currency = "KZT",
        shares = mapOf(alice.id to 1L, bob.id to 1L))
    }
    // Bob owes Alice 150.00 USD; he hands over 60.00 of it, not the lot.
    log.add(bob) { id ->
      Put(id, title = "Payback", cents = 6_000, payer = bob.id, kind = Kind.REIMBURSEMENT,
        currency = "USD", shares = mapOf(alice.id to 1L))
    }

    val state = fold(log.ops)
    assertEachSumsToZero(state)
    assertEquals(9_000L, state.balances.getValue("USD").getValue(alice.id))
    assertEquals(-9_000L, state.balances.getValue("USD").getValue(bob.id))
    // The KZT side is untouched: a payback in one currency says nothing about any other.
    assertEquals(-450_000L, state.balances.getValue("KZT").getValue(alice.id))
    assertEquals(450_000L, state.balances.getValue("KZT").getValue(bob.id))
  }

  @Test
  fun `a conversion is net zero in both currencies and moves the two people opposite ways`() {
    val log = trip()
    // Alice fronted a USD hotel, Bob fronted the KZT taxi, so each owes the other in a currency.
    log.add(alice) { id ->
      Put(id, title = "Hotel", cents = 20_000, payer = alice.id, currency = "USD",
        shares = mapOf(alice.id to 1L, bob.id to 1L))
    }
    log.add(bob) { id ->
      Put(id, title = "Taxi", cents = 9_500_000, payer = bob.id, currency = "KZT",
        shares = mapOf(alice.id to 1L, bob.id to 1L))
    }
    val before = fold(log.ops)
    // Bob owes Alice 100.00 USD, Alice owes Bob 47500.00 KZT. Neither debt can pay the other off
    // without somebody naming a rate, which is exactly what the conversion op is for.
    assertEquals(-10_000L, before.balances.getValue("USD").getValue(bob.id))
    assertEquals(-4_750_000L, before.balances.getValue("KZT").getValue(alice.id))

    // They agree 475 KZT to the dollar, which happens to cancel both debts outright.
    log.add(bob) { id ->
      Put(id, title = "Exchange", cents = 10_000, payer = bob.id, kind = Kind.CONVERSION,
        currency = "USD", toCents = 4_750_000, toCurrency = "KZT", shares = mapOf(alice.id to 1L))
    }

    val state = fold(log.ops)
    assertEachSumsToZero(state)
    // Bob's USD leg reads as a payment to Alice, and the KZT leg mirrors it back the other way,
    // so the pair settles without a cent of real money moving.
    assertEquals(0L, state.balances.getValue("USD").getValue(bob.id))
    assertEquals(0L, state.balances.getValue("USD").getValue(alice.id))
    assertEquals(0L, state.balances.getValue("KZT").getValue(alice.id))
    assertEquals(0L, state.balances.getValue("KZT").getValue(bob.id))
  }

  @Test
  fun `a conversion at a worse rate leaves the difference owing rather than losing it`() {
    val log = trip()
    log.add(alice) { id ->
      Put(id, title = "Hotel", cents = 20_000, payer = alice.id, currency = "USD",
        shares = mapOf(alice.id to 1L, bob.id to 1L))
    }
    log.add(bob) { id ->
      Put(id, title = "Taxi", cents = 9_500_000, payer = bob.id, currency = "KZT",
        shares = mapOf(alice.id to 1L, bob.id to 1L))
    }
    // Same 100.00 USD, but valued at only 400 KZT to the dollar this time.
    log.add(bob) { id ->
      Put(id, title = "Exchange", cents = 10_000, payer = bob.id, kind = Kind.CONVERSION,
        currency = "USD", toCents = 4_000_000, toCurrency = "KZT", shares = mapOf(alice.id to 1L))
    }

    val state = fold(log.ops)
    assertEachSumsToZero(state)
    assertEquals(0L, state.balances.getValue("USD").getValue(bob.id))
    // Alice's KZT debt only partly cleared; the shortfall stays visible instead of evaporating.
    assertEquals(-750_000L, state.balances.getValue("KZT").getValue(alice.id))
    assertEquals(750_000L, state.balances.getValue("KZT").getValue(bob.id))
  }

  @Test
  fun `a conversion introduces a currency nobody had spent in yet`() {
    val log = trip()
    log.add(alice) { id ->
      Put(id, cents = 5_000, payer = alice.id, currency = "USD", shares = mapOf(bob.id to 1L))
    }
    log.add(bob) { id ->
      Put(id, cents = 5_000, payer = bob.id, kind = Kind.CONVERSION, currency = "USD",
        toCents = 4_567, toCurrency = "EUR", shares = mapOf(alice.id to 1L))
    }

    val state = fold(log.ops)
    assertEquals(setOf("USD", "EUR"), state.balances.keys)
    assertEachSumsToZero(state)
    assertEquals(-4_567L, state.balances.getValue("EUR").getValue(bob.id))
    assertEquals(4_567L, state.balances.getValue("EUR").getValue(alice.id))
  }

  @Test
  fun `a malformed conversion with several participants still sums to zero on both sides`() {
    val log = trip()
    // Nothing in the UI can produce this, but a hostile or buggy peer can sign it, and an odd
    // toCents across three people is exactly where a naive mirror would lose a cent.
    log.add(alice) { id ->
      Put(id, cents = 1_000, payer = alice.id, kind = Kind.CONVERSION, currency = "USD",
        toCents = 1_001, toCurrency = "RUB", shares = everyone.associateWith { 1L })
    }
    assertEachSumsToZero(fold(log.ops))
  }

  @Test
  fun `a conversion with no target currency is inert in the other direction`() {
    val log = trip()
    log.add(alice) { id ->
      Put(id, cents = 1_000, payer = alice.id, kind = Kind.CONVERSION, currency = "USD",
        toCents = 500, toCurrency = "", shares = mapOf(bob.id to 1L))
    }
    val state = fold(log.ops)
    assertEquals(setOf("USD"), state.balances.keys)
    assertEachSumsToZero(state)
  }

  @Test
  fun `currency survives an edit by someone else and folding is still order independent`() {
    val log = trip()
    val created = log.add(alice) { id ->
      Put(id, title = "Hotel", cents = 30_000, payer = alice.id, currency = "USD",
        shares = everyone.associateWith { 1L })
    }
    log.add(bob) { Put(created.key, title = "Hotel", cents = 30_000, payer = alice.id, currency = "EGP",
      shares = everyone.associateWith { 1L }) }
    log.add(carol) { id ->
      Put(id, cents = 7_777, payer = carol.id, currency = "RUB", shares = everyone.associateWith { 1L })
    }

    val state = fold(log.ops)
    // The edit moved the expense wholesale into EGP; USD has nothing left in it at all.
    assertFalse(state.balances.containsKey("USD"))
    assertTrue(state.balances.containsKey("EGP"))
    assertEquals(state, fold(log.ops.shuffled()))
    assertEquals(state, fold(log.ops.reversed()))
  }
}
