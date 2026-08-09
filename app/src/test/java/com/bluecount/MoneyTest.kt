package com.bluecount

import com.bluecount.ui.Invite
import com.bluecount.ui.MICRO
import com.bluecount.ui.micros
import com.bluecount.ui.money
import com.bluecount.ui.parseInvite
import com.bluecount.ui.rateMicros
import com.bluecount.ui.toCentsOrNull
import com.bluecount.ui.toMicrosOrNull
import com.bluecount.ui.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
  @Test
  fun `cents render without floating point`() {
    assertEquals("0.00", 0L.money())
    assertEquals("0.05", 5L.money())
    assertEquals("1.00", 100L.money())
    assertEquals("123.45", 12345L.money())
    assertEquals("-7.09", (-709L).money())
  }

  @Test
  fun `typed amounts parse to exact cents`() {
    assertEquals(1200L, "12".toCentsOrNull())
    assertEquals(1230L, "12.3".toCentsOrNull())
    assertEquals(1234L, "12,34".toCentsOrNull())
    assertEquals(1200L, " 12 ".toCentsOrNull())
    // Anything ambiguous is rejected rather than silently rounded.
    assertNull("12.345".toCentsOrNull())
    assertNull("1e3".toCentsOrNull())
    assertNull("-5".toCentsOrNull())
    assertNull("".toCentsOrNull())
  }

  @Test
  fun `amount survives a round trip through the editor`() {
    for (cents in listOf(1L, 99L, 100L, 4321L, 1_000_000L)) {
      assertEquals(cents, cents.money().toCentsOrNull())
    }
  }

  @Test
  fun `an exchange rate round trips through the rate field without drifting`() {
    assertEquals(475_000_000L, "475".toMicrosOrNull())
    assertEquals(1_234_500L, "1.2345".toMicrosOrNull())
    assertEquals(1_234_500L, "1,2345".toMicrosOrNull())
    assertNull("1.2345678".toMicrosOrNull())
    assertNull("-1".toMicrosOrNull())

    assertEquals("475", 475_000_000L.micros())
    assertEquals("1.2345", 1_234_500L.micros())
    assertEquals("0", 0L.micros())
    for (m in listOf(1L, 500_000L, 1_000_000L, 475_000_000L, 1_234_500L)) {
      assertEquals(m, m.micros().toMicrosOrNull())
    }
  }

  @Test
  fun `the rate the editor shows is the one the two stored amounts imply`() {
    // 100.00 USD for 47500.00 KZT is 475 KZT per USD, and typing that rate reproduces the amount.
    assertEquals(475_000_000L, rateMicros(10_000, 4_750_000))
    assertEquals(4_750_000L, 10_000L * rateMicros(10_000, 4_750_000) / MICRO)
    // Division by nothing is not an error, just a rate of zero — the field is empty until it is not.
    assertEquals(0L, rateMicros(0, 1_000))
  }

  @Test
  fun `invite QR round trips, including awkward names`() {
    val invite = Invite("aGVsbG8td29ybGQ", "Bob & Alice's trip? #2", "SEK")
    assertEquals(invite, parseInvite(invite.toUri()))
  }

  @Test
  fun `anything that is not an invite is rejected`() {
    assertNull(parseInvite("https://example.com"))
    assertNull(parseInvite("bluecount:"))
    assertEquals(Invite("abc", "", "EUR"), parseInvite("bluecount:abc"))
  }
}
