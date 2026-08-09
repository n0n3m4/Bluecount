package com.bluecount

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Anti-entropy between two phones that happen to be near each other.
 *
 * Deliberately symmetric: there is no "creator serves, listeners fetch". Every peer stores and
 * serves *everyone's* signed ops, so a group converges even when the person who paid for dinner is
 * already on a plane home. Relaying is safe precisely because ops are signed — a relay can withhold
 * data but cannot forge it.
 */

/** One live connection. Callbacks rather than flows, because that is what Nearby hands us. */
interface Peer {
  val name: String

  fun send(bytes: ByteArray)

  fun close()

  var onMessage: ((ByteArray) -> Unit)?
  var onClosed: (() -> Unit)?
}

interface Transport {
  fun start(onPeer: (Peer) -> Unit)

  fun stop()
}

/** What a [Session] needs from storage. [Repo] is the real one; tests use an in-memory stand-in. */
interface OpStore {
  val me: UserId

  suspend fun clocks(): Map<String, Clock>

  suspend fun opsFor(theirClocks: Map<String, Clock>): List<Op>

  suspend fun merge(ops: List<Op>): Int
}

data class OpKey(val event: String, val author: String, val seq: Long)

/**
 * Per event, the highest seq per author for which we hold an unbroken run from 1.
 *
 * Advertising the *contiguous* prefix rather than the maximum is what makes gaps heal: an op that
 * arrives out of order is stored but not counted, so the peer keeps resending the hole until it is
 * filled, and only then does the clock jump past it.
 *
 * Every joined event appears, including ones with no ops at all — otherwise someone who has only
 * just scanned the QR advertises nothing, the peer sees no event in common, and the newcomer is
 * never sent anything.
 */
fun contiguousClocks(keys: List<OpKey>, joinedEvents: Set<String>): Map<String, Clock> {
  val held =
    keys.groupBy { it.event }.mapValues { (_, rows) ->
      rows.groupBy { it.author }.mapValues { (_, own) ->
        var n = 0L
        for (seq in own.map { it.seq }.sorted()) {
          if (seq == n + 1) n = seq else if (seq > n + 1) break
        }
        n
      }
    }
  return joinedEvents.associateWith { held[it] ?: emptyMap() }
}

/** Everything the peer is missing, for events we both have. Authors they never mention count as 0. */
fun selectMissing(ops: List<Op>, theirClocks: Map<String, Clock>): List<Op> =
  ops.filter { op ->
    val theirs = theirClocks[op.event] ?: return@filter false
    op.seq > (theirs[op.author] ?: 0L)
  }

/**
 * The trust boundary. Everything else in the sync path can assume its input is signed and wanted.
 *
 * Ops for events we never joined are dropped rather than stored, so being near someone cannot pull
 * their trip onto your phone — you join by scanning a QR (spec §4).
 */
fun acceptable(ops: List<Op>, joinedEvents: Set<String>): List<Op> =
  ops.filter { it.event in joinedEvents && it.verified() }

/** Keeps every payload under Nearby's 32 KiB BYTES limit. Pure, so it is cheap to test. */
fun batches(ops: List<Op>): List<List<Op>> {
  val out = mutableListOf<List<Op>>()
  var current = mutableListOf<Op>()
  var size = 0
  for (op in ops) {
    val cost = op.payload.size + op.sig.size + op.author.length + op.event.length + 48
    if (current.isNotEmpty() && size + cost > MAX_BATCH_BYTES) {
      out += current
      current = mutableListOf()
      size = 0
    }
    current += op
    size += cost
  }
  if (current.isNotEmpty()) out += current
  return out
}

/**
 * Runs the exchange on one connection: both sides open with [Hello] carrying a per-event vector
 * clock, then each pushes what the other is missing. No request round-trip, no roles.
 */
class Session(
  private val peer: Peer,
  private val store: OpStore,
  private val scope: CoroutineScope,
  private val onMerged: (Int) -> Unit = {},
) {
  private val lock = Mutex()
  private var sentAll = false
  private var receivedAll = false
  private var closed = false

  fun start() {
    peer.onMessage = { bytes -> scope.launch { lock.withLock { handle(bytes) } } }
    peer.onClosed = { closed = true }
    scope.launch { lock.withLock { peer.send(Hello(store.me, store.clocks()).encode()) } }
  }

  private suspend fun handle(bytes: ByteArray) {
    when (val msg = decodeMsg(bytes)) {
      is Hello -> {
        val chunks = batches(store.opsFor(msg.have))
        if (chunks.isEmpty()) {
          peer.send(OpBatch(emptyList(), more = false).encode())
        } else {
          chunks.forEachIndexed { i, chunk -> peer.send(OpBatch(chunk, more = i < chunks.lastIndex).encode()) }
        }
        sentAll = true
        finishIfDone()
      }
      is OpBatch -> {
        if (msg.ops.isNotEmpty()) onMerged(store.merge(msg.ops))
        if (!msg.more) {
          receivedAll = true
          finishIfDone()
        }
      }
      // Garbage from an unauthenticated peer: hang up rather than guess.
      null -> peer.close()
    }
  }

  private fun finishIfDone() {
    if (sentAll && receivedAll && !closed) {
      closed = true
      peer.close()
    }
  }
}
