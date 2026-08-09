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
  /** UTC millis, or 0 on an op written before expenses carried a time. See [Put.at]. */
  val at: Long,
  val payer: UserId,
  val mode: SplitMode,
  val shares: Map<UserId, Long>,
  val kind: Kind,
  /** Always resolved — [Put.currency] blank means the event default, and that is settled here. */
  val currency: String,
  /** [Kind.CONVERSION] only: the amount handed back the other way. Zero otherwise. */
  val toCents: Long,
  val toCurrency: String,
  /** Author of the most recent op touching this expense — anyone in the event may edit. */
  val lastEditor: UserId,
)

data class Transfer(val from: UserId, val to: UserId, val cents: Long)

data class EventState(
  val name: String,
  /** The event default, from [Genesis]. What the currency pickers pre-select; not a total. */
  val currency: String,
  /** Every id ever seen in this event, mapped to its nickname (or a short id if never claimed). */
  val members: Map<UserId, String>,
  val expenses: List<Expense>,
  /**
   * Balances per currency, counted **independently** — nothing here is ever converted, because a
   * rate is a matter of opinion and two phones must not hold different opinions. Positive = the
   * group owes them. Every inner map sums to zero. Ordered event default first, then alphabetically.
   */
  val balances: Map<String, Map<UserId, Long>>,
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
        // Resolved here rather than in the loop above: a Put can sort ahead of the Genesis that
        // names the default, and an expense must not depend on which order it was folded in.
        Expense(
          p.id, p.title, p.cents, p.date, p.at, p.payer, p.mode, p.shares, p.kind,
          p.currency.ifBlank { currency }, p.toCents, p.toCurrency, editor,
        )
      }
      // at is UTC millis and date an epoch day, so an op with no time sorts as its own UTC midnight.
      .sortedWith(
        compareByDescending<Expense> { if (it.at != 0L) it.at else it.date * 86_400_000L }
          .thenByDescending { it.id }
      )

  val members = (seen + expenses.flatMap { it.shares.keys + it.payer }).filter { it.isNotEmpty() }
  val balances = mutableMapOf<String, MutableMap<UserId, Long>>()
  for (e in expenses) {
    val b = balances.getOrPut(e.currency) { mutableMapOf() }
    b[e.payer] = (b[e.payer] ?: 0L) + e.cents
    for ((who, owed) in split(e.cents, e.mode, e.shares)) b[who] = (b[who] ?: 0L) - owed
    // A conversion is the same two people mirrored in the other currency, so each currency stays
    // net zero on its own. split() rather than assuming one participant: it sums back exactly even
    // if a peer signs a conversion with several, so the zero-sum invariant survives a malformed op.
    if (e.kind == Kind.CONVERSION && e.toCurrency.isNotEmpty() && e.toCents > 0) {
      val t = balances.getOrPut(e.toCurrency) { mutableMapOf() }
      t[e.payer] = (t[e.payer] ?: 0L) - e.toCents
      for ((who, got) in split(e.toCents, SplitMode.EQUAL, e.shares)) t[who] = (t[who] ?: 0L) + got
    }
  }

  // Event default first so the common single-currency event reads as it always did.
  val ordered = balances.keys.sortedBy { if (it == currency) "" else it }
  return EventState(
    name,
    currency,
    members.associateWith { nicks[it] ?: it.shortId() },
    expenses,
    ordered.associateWith { balances.getValue(it).toMap() },
  )
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
