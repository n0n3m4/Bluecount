package com.bluecount

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTest {
  private val abc = listOf("alice", "bob", "carol")

  @Test
  fun `equal split loses no cents`() {
    val out = split(1000, SplitMode.EQUAL, abc.associateWith { 1L })
    assertEquals(1000L, out.values.sum())
    assertEquals(listOf(333L, 333L, 334L), out.values.sorted())
  }

  @Test
  fun `leftover cents land on the same people regardless of map order`() {
    val forward = split(1000, SplitMode.EQUAL, abc.associateWith { 1L })
    val backward = split(1000, SplitMode.EQUAL, abc.reversed().associateWith { 1L })
    assertEquals(forward.toSortedMap(), backward.toSortedMap())
    // Deterministic in value too, not just in shape: 'alice' sorts first, so she takes the extra.
    assertEquals(334L, forward["alice"])
  }

  @Test
  fun `weighted split respects shares and still sums exactly`() {
    val out = split(1001, SplitMode.SHARES, mapOf("alice" to 2L, "bob" to 1L))
    assertEquals(1001L, out.values.sum())
    assertTrue(out.getValue("alice") > out.getValue("bob"))
  }

  @Test
  fun `exact split is used verbatim when it adds up`() {
    val exact = mapOf("alice" to 700L, "bob" to 300L)
    assertEquals(exact, split(1000, SplitMode.EXACT, exact))
  }

  @Test
  fun `exact split that does not add up still conserves the total`() {
    val out = split(1000, SplitMode.EXACT, mapOf("alice" to 700L, "bob" to 200L))
    assertEquals(1000L, out.values.sum())
  }

  @Test
  fun `no amount is ever lost or invented, for any input`() {
    val rnd = Random(1)
    repeat(2000) {
      val n = 1 + rnd.nextInt(6)
      val people = (0 until n).map { i -> "p$i" }
      val cents = 1L + rnd.nextInt(100_000)
      val mode = SplitMode.entries[rnd.nextInt(3)]
      val shares = people.associateWith { rnd.nextInt(5).toLong() }
      val out = split(cents, mode, shares)
      assertEquals("mode=$mode cents=$cents shares=$shares", cents, out.values.sum())
      assertTrue(out.values.all { it >= 0 })
    }
  }
}
