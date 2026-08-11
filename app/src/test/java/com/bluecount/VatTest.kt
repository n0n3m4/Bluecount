package com.bluecount

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VAT is a percentage the *editor* knows about and the ledger does not: what gets stored is the
 * inclusive total, exactly as before the feature existed. These tests pin both halves of that — the
 * arithmetic the editor does on the way in and out, and the fact that none of it reaches a balance.
 */
class VatTest {
  private val alice = testSigner()
  private val bob = testSigner()
  private val both = listOf(alice, bob).map { it.id }

  private fun trip(): TestLog =
    TestLog().apply {
      add(alice, Genesis("Dinner", "USD"))
      add(alice, Profile("Alice"))
      add(bob, Profile("Bob"))
    }

  @Test
  fun `a subtotal grosses up and comes back unchanged`() {
    assertEquals(11_000L, grossOf(10_000, 1_000)) // 100.00 + 10%
    assertEquals(10_000L, netOf(11_000, 1_000))
    assertEquals(10_825L, grossOf(10_000, 825)) // 8.25% sales tax
    assertEquals(10_000L, netOf(10_825, 825))
    // No VAT means the number is left alone, which is what keeps old expenses reading as they did.
    assertEquals(4_321L, grossOf(4_321, 0))
    assertEquals(4_321L, netOf(4_321, 0))
    // A nonsense rate is treated as none rather than overflowing a Long on the way past.
    assertEquals(4_321L, grossOf(4_321, 1_000_000))
  }

  @Test
  fun `the subtotal shown on reopening is the one that was typed`() {
    val rnd = Random(7)
    var offBy = 0
    repeat(5000) {
      val net = 1L + rnd.nextInt(2_000_000)
      val bp = rnd.nextInt(BP.toInt() + 1).toLong()
      val back = netOf(grossOf(net, bp), bp)
      // Half-up both ways is its own inverse for anything a bill actually says, but not for every
      // (amount, rate) pair — 0.01 at 50% grosses to 0.02, which divides back to 0.01 only because
      // of the rounding. Where it does bite, it bites by a single cent and never more.
      assertTrue("net=$net bp=$bp back=$back", abs(back - net) <= 1)
      if (back != net) offBy++
    }
    // ...and it is rare enough that reopening and re-saving an expense is not a game of chance.
    assertTrue("$offBy of 5000 did not round trip", offBy < 50)
  }

  @Test
  fun `exact parts grossed up still sum to the stored total`() {
    val rnd = Random(11)
    repeat(2000) {
      val n = 1 + rnd.nextInt(5)
      val parts = (0 until n).associate { i -> "p$i" to 1L + rnd.nextInt(50_000) }
      val bp = rnd.nextInt(BP.toInt() + 1).toLong()
      val gross = grossOf(parts.values.sum(), bp)
      val stored = split(gross, SplitMode.SHARES, parts)
      assertEquals("parts=$parts bp=$bp", gross, stored.values.sum())
      // Stored EXACT parts that add up are used verbatim, so what the editor showed is what is owed.
      assertEquals(stored, split(gross, SplitMode.EXACT, stored))
    }
  }

  @Test
  fun `a VAT expense balances exactly like the same total without one`() {
    val plain =
      trip().apply {
        add(alice) { id -> Put(id, title = "Dinner", cents = 11_000, payer = alice.id, shares = both.associateWith { 1L }) }
      }
    val taxed =
      trip().apply {
        add(alice) { id ->
          Put(id, title = "Dinner", cents = 11_000, payer = alice.id, vatBp = 1_000, shares = both.associateWith { 1L })
        }
      }
    assertEquals(fold(plain.ops).balances, fold(taxed.ops).balances)
    assertEquals(0L, fold(taxed.ops).balances.getValue("USD").values.sum())
    assertEquals(1_000L, fold(taxed.ops).expenses.single().vatBp)
    // Folding order still does not matter — vatBp rides along, it does not participate.
    assertEquals(fold(taxed.ops), fold(taxed.ops.shuffled()))
  }

  @Test
  fun `an op written before VAT existed decodes as having none`() {
    val old = """{"t":"x","id":"a:1","cents":11000,"payer":"a"}""".toByteArray()
    assertEquals(0L, (decodePayload(old) as Put).vatBp)
  }
}
