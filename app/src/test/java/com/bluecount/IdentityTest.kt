package com.bluecount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {
  /**
   * A P-256 SPKI has a fixed 27-byte DER header, so any prefix of a user ID is the same for
   * everyone. NearbyTransport advertises shortId() and only dials when its own is the smaller,
   * so a colliding shortId means two phones never connect at all.
   */
  @Test
  fun shortIdsDiffer() {
    val a = testSigner().id
    val b = testSigner().id
    assertNotEquals(a.shortId(), b.shortId())
  }

  /** The whole point of the key file: the identity survives being written down and read back. */
  @Test
  fun identityRoundTrips() {
    val text = newIdentity()
    val signer = parseIdentity(text)!!
    assertEquals(signer.id, parseIdentity(text)!!.id)
    val op = Op.create(signer, "trip", 1, 1, Profile("Ann"))
    assertTrue(op.verified())
  }

  @Test
  fun garbageIsRejected() {
    for (bad in listOf("", "   ", "not a key", newIdentity().lines()[0], newIdentity() + "\nextra", "%%%\n%%%"))
      assertNull(bad, parseIdentity(bad))
  }

  /**
   * The case the probe signature exists for: both halves parse, but they are from different keys.
   * Without the check this signs ops that every peer, forever, rejects as forged.
   */
  @Test
  fun mismatchedHalvesAreRejected() {
    val a = newIdentity().lines()
    val b = newIdentity().lines()
    assertNull(parseIdentity(a[0] + "\n" + b[1]))
  }
}
