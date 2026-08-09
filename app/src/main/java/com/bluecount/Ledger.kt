package com.bluecount

/**
 * Everything the UI shows, derived by folding the op log. Pure and Android-free: same ops in, same
 * state out, on every device — which is the whole point, since there is no server to arbitrate.
 */

data class Expense(
  val id: String,
  val title: String,
  val cents: Long,
  val date: Long,
  val payer: UserId,
  val mode: SplitMode,
  val shares: Map<UserId, Long>,
  val kind: Kind,
  /** Author of the most recent op touching this expense — anyone in the event may edit. */
  val lastEditor: UserId,
)

data class Transfer(val from: UserId, val to: UserId, val cents: Long)

data class EventState(
  val name: String,
  val currency: String,
  /** Every id ever seen in this event, mapped to its nickname (or a short id if never claimed). */
  val members: Map<UserId, String>,
  val expenses: List<Expense>,
  /** Positive = the group owes them. Always sums to zero. */
  val balances: Map<UserId, Long>,
) {
  fun nick(id: UserId): String = members[id] ?: id.shortId()
}

/**
 * The op that wins is the last one in `(lamport, author, seq)` order. [Op.lamport] is what makes
 * this comparable across authors; the spec's per-author seq alone is not.
 */
private val opOrder = compareBy<Op>({ it.lamport }, { it.author }, { it.seq })

fun fold(ops: List<Op>, fallbackName: String = "", fallbackCurrency: String = ""): EventState {
  var name = fallbackName
  var currency = fallbackCurrency
  var haveGenesis = false
  val nicks = mutableMapOf<UserId, String>()
  val puts = LinkedHashMap<String, Pair<Put, UserId>>()
  val seen = mutableSetOf<UserId>()

  for (op in ops.sortedWith(opOrder)) {
    seen += op.author
    when (val p = decodePayload(op.payload)) {
      // First genesis in total order wins, so a second one cannot rename the event under you.
      is Genesis -> if (!haveGenesis) { name = p.name; currency = p.currency; haveGenesis = true }
      is Profile -> nicks[op.author] = p.nick
      is Put -> puts[p.id] = p to op.author
      null -> Unit // an op type from a newer version: stored and relayed, just not understood here
    }
  }

  val expenses =
    puts.values
      .filterNot { (p, _) -> p.deleted }
      .map { (p, editor) ->
        Expense(p.id, p.title, p.cents, p.date, p.payer, p.mode, p.shares, p.kind, editor)
      }
      .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })

  val members = (seen + expenses.flatMap { it.shares.keys + it.payer }).filter { it.isNotEmpty() }
  val balances = members.associateWith { 0L }.toMutableMap()
  for (e in expenses) {
    balances[e.payer] = (balances[e.payer] ?: 0L) + e.cents
    for ((who, owed) in split(e.cents, e.mode, e.shares)) {
      balances[who] = (balances[who] ?: 0L) - owed
    }
  }

  return EventState(name, currency, members.associateWith { nicks[it] ?: it.shortId() }, expenses, balances)
}

/**
 * Splits [cents] with **no rounding loss**: the result always sums back to [cents] exactly, and the
 * leftover cents land on the same people on every device (largest remainder, ties broken by user
 * id). Get this wrong and two phones quietly disagree about who owes what.
 */
fun split(cents: Long, mode: SplitMode, shares: Map<UserId, Long>): Map<UserId, Long> {
  if (shares.isEmpty() || cents <= 0) return emptyMap()

  // An EXACT split whose parts do not add up would break the sum invariant. Rather than drop the
  // expense, reinterpret the numbers as weights — deterministic, and visibly wrong in the UI.
  if (mode == SplitMode.EXACT && shares.values.sum() == cents) return shares

  val weights =
    when {
      mode == SplitMode.EQUAL -> shares.keys.associateWith { 1L }
      shares.values.any { it > 0 } -> shares.filterValues { it > 0 }
      else -> shares.keys.associateWith { 1L }
    }
  val total = weights.values.sum()
  val ids = weights.keys.sorted()

  val out = ids.associateWith { cents * weights.getValue(it) / total }.toMutableMap()
  var leftover = cents - out.values.sum()
  // Biggest fractional part first; the id sort makes ties resolve identically everywhere.
  for (id in ids.sortedWith(compareByDescending<UserId> { cents * weights.getValue(it) % total }.thenBy { it })) {
    if (leftover <= 0) break
    out[id] = out.getValue(id) + 1
    leftover--
  }
  return out
}

/**
 * Greedy largest-debtor-to-largest-creditor, which is what Tricount's "settle up" does: at most
 * n-1 transfers instead of everyone paying everyone.
 *
 * ponytail: greedy, not optimal — minimising transfer *count* exactly is NP-hard and nobody on a
 * ski trip will notice one extra payment.
 */
fun settleUp(balances: Map<UserId, Long>): List<Transfer> {
  val debtors = balances.filter { it.value < 0 }.map { it.key to -it.value }.toMutableList()
  val creditors = balances.filter { it.value > 0 }.map { it.key to it.value }.toMutableList()
  debtors.sortWith(compareByDescending<Pair<UserId, Long>> { it.second }.thenBy { it.first })
  creditors.sortWith(compareByDescending<Pair<UserId, Long>> { it.second }.thenBy { it.first })

  val out = mutableListOf<Transfer>()
  var d = 0
  var c = 0
  while (d < debtors.size && c < creditors.size) {
    val amount = minOf(debtors[d].second, creditors[c].second)
    if (amount > 0) out += Transfer(debtors[d].first, creditors[c].first, amount)
    debtors[d] = debtors[d].first to debtors[d].second - amount
    creditors[c] = creditors[c].first to creditors[c].second - amount
    if (debtors[d].second == 0L) d++
    if (creditors[c].second == 0L) c++
  }
  return out
}
