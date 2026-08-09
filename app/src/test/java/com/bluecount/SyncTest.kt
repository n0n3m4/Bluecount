package com.bluecount

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncTest {
  private val alice = testSigner()
  private val bob = testSigner()
  private val carol = testSigner()
  private val event = "trip"

  private fun log() =
    TestLog(event).apply {
      add(alice, Genesis("Ski trip", "EUR"))
      add(alice, Profile("Alice"))
      add(bob, Profile("Bob"))
    }

  /** Runs one full exchange between two stores over a fake radio. */
  private suspend fun TestScope.sync(left: MemStore, right: MemStore, reorder: Boolean = false) {
    val wire = Loopback()
    Session(wire.a, left, this).start()
    Session(wire.b, right, this).start()
    repeat(50) {
      advanceUntilIdle()
      if (!wire.deliverOnce(reorder)) return
    }
  }

  @Test
  fun `a peer with nothing catches up`() = runTest {
    val log = log()
    log.add(alice) { id -> Put(id, title = "Hotel", cents = 30_000, payer = alice.id, shares = mapOf(alice.id to 1L, bob.id to 1L)) }
    val a = MemStore(alice.id, setOf(event)).apply { seed(log.ops) }
    val b = MemStore(bob.id, setOf(event))

    sync(a, b)

    assertEquals(a.ops.size, b.ops.size)
    assertEquals(fold(a.ops), fold(b.ops))
  }

  @Test
  fun `both sides push at once`() = runTest {
    val log = log()
    val shared = log.ops.toList()
    val a = MemStore(alice.id, setOf(event)).apply { seed(shared) }
    val b = MemStore(bob.id, setOf(event)).apply { seed(shared) }

    // Each adds something the other has never seen.
    a.seed(listOf(log.add(alice, Profile("Alice A"))))
    b.seed(listOf(log.add(bob, Profile("Bob B"))))

    sync(a, b)

    assertEquals(fold(a.ops), fold(b.ops))
    assertEquals(shared.size + 2, a.ops.size)
  }

  @Test
  fun `expenses reach someone who never met their author`() = runTest {
    // The star topology in the original spec cannot do this: Carol only ever meets Bob, yet she
    // must still end up with Alice's expenses.
    val log = log()
    log.add(alice) { id -> Put(id, title = "Hotel", cents = 30_000, payer = alice.id, shares = mapOf(alice.id to 1L, bob.id to 1L)) }
    val a = MemStore(alice.id, setOf(event)).apply { seed(log.ops) }
    val b = MemStore(bob.id, setOf(event))
    val c = MemStore(carol.id, setOf(event))

    sync(a, b)
    sync(b, c) // Alice is long gone at this point.

    assertEquals(fold(a.ops), fold(c.ops))
    assertEquals("Hotel", fold(c.ops).expenses.single().title)
  }

  @Test
  fun `ops arriving out of order still converge`() = runTest {
    val log = log()
    repeat(5) { i -> log.add(alice) { id -> Put(id, title = "Round $i", cents = 100L * (i + 1), payer = alice.id, shares = mapOf(alice.id to 1L, bob.id to 1L)) } }
    val a = MemStore(alice.id, setOf(event)).apply { seed(log.ops) }
    val b = MemStore(bob.id, setOf(event))

    sync(a, b, reorder = true)

    assertEquals(fold(a.ops), fold(b.ops))
  }

  @Test
  fun `a gap in the log is advertised as not-yet-held and gets refilled`() = runTest {
    val log = log()
    val a = MemStore(alice.id, setOf(event)).apply { seed(log.ops) }
    // Bob somehow holds Alice's seq 2 but not seq 1.
    val b = MemStore(bob.id, setOf(event)).apply { seed(log.ops.filter { it.author == alice.id && it.seq == 2L }) }

    assertEquals(0L, b.clocks().getValue(event)[alice.id])
    sync(a, b)
    assertEquals(fold(a.ops), fold(b.ops))
  }

  @Test
  fun `a tampered op is refused`() = runTest {
    val log = log()
    val good = log.ops.last()
    val forged = Op(good.event, good.author, good.seq, good.lamport, Profile("Not Bob").encode(), good.sig)

    val victim = MemStore(carol.id, setOf(event))
    assertEquals(0, victim.merge(listOf(forged)))
    assertEquals(log.ops.size - 1, victim.merge(log.ops.dropLast(1)))
  }

  @Test
  fun `an author who signs two different ops at the same seq gains nothing`() = runTest {
    val first = Op.create(alice, event, 1, 1, Profile("Alice"))
    val second = Op.create(alice, event, 1, 1, Profile("Alice the second"))
    assertTrue(first.verified() && second.verified())
    assertNotEquals(first.payload.decodeToString(), second.payload.decodeToString())

    val store = MemStore(bob.id, setOf(event))
    store.merge(listOf(first))
    store.merge(listOf(second))

    assertEquals(1, store.ops.size)
    assertEquals("Alice", (decodePayload(store.ops.single().payload) as Profile).nick)
  }

  @Test
  fun `ops for an event we never joined are dropped`() = runTest {
    val other = TestLog("someone-elses-trip").apply { add(alice, Genesis("Not mine", "USD")) }
    val store = MemStore(bob.id, setOf(event))
    assertEquals(0, store.merge(other.ops))
    assertTrue(store.ops.isEmpty())
  }

  @Test
  fun `batches stay under the Nearby payload limit`() {
    val log = TestLog(event)
    repeat(400) { i -> log.add(alice) { id -> Put(id, title = "Expense number $i with a reasonably long description", cents = 1234, payer = alice.id, shares = mapOf(alice.id to 1L)) } }

    val chunks = batches(log.ops)
    assertTrue("expected several batches, got ${chunks.size}", chunks.size > 1)
    assertEquals(log.ops.size, chunks.sumOf { it.size })
    chunks.forEach { assertTrue(OpBatch(it, true).encode().size <= 32 * 1024) }
  }
}
