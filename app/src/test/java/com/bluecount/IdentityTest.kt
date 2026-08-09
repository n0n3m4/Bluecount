package com.bluecount

import org.junit.Assert.assertNotEquals
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
}
