package com.bluecount

/**
 * Two [Peer]s wired straight to each other, standing in for a radio.
 *
 * This is why the sync engine takes a [Transport] at all: Nearby Connections needs real Bluetooth
 * and Wi-Fi hardware, so it cannot run on an emulator and the protocol would otherwise be
 * untestable without two physical phones in the room.
 */
class Loopback {
  private inner class Side(override val name: String) : Peer {
    lateinit var other: Side
    override var onMessage: ((ByteArray) -> Unit)? = null
    override var onClosed: (() -> Unit)? = null
    var open = true
    /** Held rather than delivered, so a test can choose the arrival order. */
    val queue = ArrayDeque<ByteArray>()

    override fun send(bytes: ByteArray) {
      if (open && other.open) other.queue.addLast(bytes)
    }

    override fun close() {
      if (!open) return
      open = false
      onClosed?.invoke()
    }
  }

  val a: Peer = Side("a")
  val b: Peer = Side("b")

  init {
    (a as Side).other = b as Side
    b.other = a
  }

  /**
   * Hands over whatever is queued right now, returning false once both sides are quiet. The caller
   * drives this in a loop so it can let coroutines run in between.
   *
   * @param reorder deliver each side's queue backwards, i.e. ops arriving out of order.
   */
  fun deliverOnce(reorder: Boolean = false): Boolean {
    val sides = listOf(a as Side, b as Side)
    if (sides.all { it.queue.isEmpty() }) return false
    for (side in sides) {
      val pending = side.queue.toList().let { if (reorder) it.reversed() else it }
      side.queue.clear()
      pending.forEach { side.onMessage?.invoke(it) }
    }
    return true
  }
}

/** In-memory [OpStore], so a test peer needs neither Room nor an Android runtime. */
class MemStore(override val me: UserId, joined: Set<String>) : OpStore {
  private val joinedEvents = joined.toMutableSet()
  private val stored = LinkedHashMap<String, Op>()

  val ops: List<Op>
    get() = stored.values.toList()

  fun seed(list: List<Op>) = list.forEach { store(it) }

  /** First version of an `(event, author, seq)` wins — an equivocating author gains nothing. */
  private fun store(op: Op) {
    stored.putIfAbsent("${op.event}|${op.author}|${op.seq}", op)
  }

  override suspend fun clocks(): Map<String, Clock> =
    contiguousClocks(stored.values.map { OpKey(it.event, it.author, it.seq) }, joinedEvents)

  override suspend fun opsFor(theirClocks: Map<String, Clock>) = selectMissing(ops, theirClocks)

  override suspend fun merge(ops: List<Op>): Int {
    val good = acceptable(ops, joinedEvents)
    good.forEach { store(it) }
    return good.size
  }
}
