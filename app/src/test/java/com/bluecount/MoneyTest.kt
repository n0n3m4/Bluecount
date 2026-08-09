package com.bluecount

import com.bluecount.ui.Invite
import com.bluecount.ui.money
import com.bluecount.ui.parseInvite
import com.bluecount.ui.toCentsOrNull
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
