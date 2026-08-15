package com.bluecount

import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file a chat app carries: same ops, same signatures, and nothing a bad file can do to us. */
class ExportTest {
  private val alice = testSigner()
  private val bob = testSigner()

  private fun log(event: String = "trip") =
    TestLog(event).apply {
      add(alice, Genesis("Ski trip", "EUR"))
      add(alice, Profile("Alice"))
      add(bob, Profile("Bob"))
      add(alice) { id -> Put(id = id, title = "Lift pass", cents = 12_000, payer = alice.id) }
    }

  @Test
  fun `round trips every byte`() {
    val ops = log().ops
    val back = importOps(exportOps(ops))

    assertEquals(ops.size, back.size)
    // Byte-for-byte on payload and sig, not just equal-looking: a re-serialized payload would still
    // decode to the same object and no longer verify.
    ops.zip(back).forEach { (a, b) ->
      assertEquals(a.event, b.event)
      assertEquals(a.author, b.author)
      assertEquals(a.seq, b.seq)
      assertEquals(a.lamport, b.lamport)
      assertArrayEquals(a.payload, b.payload)
      assertArrayEquals(a.sig, b.sig)
      assertTrue(b.verified())
    }
  }

  @Test
  fun `is smaller than the raw batch`() {
    val ops = log().ops
    assertTrue(exportOps(ops).size < OpBatch(ops, more = false).encode().size)
  }

  @Test
  fun `gives up quietly on anything that is not one of ours`() {
    assertEquals(emptyList<Op>(), importOps(ByteArray(0)))
    assertEquals(emptyList<Op>(), importOps("not a bluecount file at all".toByteArray()))
    // A valid deflate stream of something that is not an OpBatch.
    assertEquals(emptyList<Op>(), importOps(deflate("hello".toByteArray())))
    // A Hello is a valid Msg but not a file.
    assertEquals(emptyList<Op>(), importOps(deflate(Hello(alice.id, emptyMap()).encode())))
    // Truncated mid-stream, which is what a half-downloaded attachment looks like.
    val whole = exportOps(log().ops)
    assertEquals(emptyList<Op>(), importOps(whole.copyOf(whole.size / 2)))
  }

  /** A few hundred bytes that inflate to gigabytes must not decide how much heap we take. */
  @Test
  fun `refuses a zip bomb`() {
    val bomb = deflate(ByteArray(4 * 1024 * 1024))
    assertTrue(bomb.size < 64 * 1024)
    assertEquals(emptyList<Op>(), importOps(bomb))
    assertEquals(null, readCapped(bomb.inputStream(), max = bomb.size - 1))
  }

  /** The import path is the sync path, so joining still gates what a file can put on the phone. */
  @Test
  fun `a file cannot inject an event you never joined`() = runTest {
    val mine = log("trip").ops
    val theirs = log("someone elses trip").ops
    val store = MemStore(bob.id, setOf("trip"))

    assertEquals(mine.size, store.merge(importOps(exportOps(mine + theirs))))
    assertTrue(store.ops.all { it.event == "trip" })

    // Full history every time, so importing the same file twice must add nothing.
    assertEquals(0, store.merge(importOps(exportOps(mine))))
  }

  private fun deflate(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    DeflaterOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
  }
}
