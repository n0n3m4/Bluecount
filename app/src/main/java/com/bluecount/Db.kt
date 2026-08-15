package com.bluecount

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Ops as received, verbatim. [payload] and [sig] are never rewritten — that is what keeps them verifiable. */
@Entity(tableName = "ops", primaryKeys = ["event", "author", "seq"])
data class OpRow(
  val event: String,
  val author: String,
  val seq: Long,
  val lamport: Long,
  val payload: ByteArray,
  val sig: ByteArray,
) {
  fun toOp() = Op(event, author, seq, lamport, payload, sig)

  // Room's data class is only ever compared by primary key in practice; array equality is not used.
  override fun equals(other: Any?) = other is OpRow && event == other.event && author == other.author && seq == other.seq

  override fun hashCode() = "$event/$author/$seq".hashCode()
}

fun Op.toRow() = OpRow(event, author, seq, lamport, payload, sig)

/**
 * An event you have joined. [name]/[currency] start as whatever the QR claimed and are replaced by
 * the signed [Genesis] op once it syncs in.
 */
@Entity(tableName = "events")
data class EventRow(
  @PrimaryKey val id: String,
  val name: String,
  val currency: String,
  val joinedAt: Long,
)

@Dao
interface OpDao {
  @Query("SELECT * FROM events ORDER BY joinedAt DESC") fun events(): Flow<List<EventRow>>

  @Query("SELECT * FROM events WHERE id = :id") fun event(id: String): Flow<EventRow?>

  @Query("SELECT * FROM events WHERE id = :id") suspend fun eventOnce(id: String): EventRow?

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEvent(row: EventRow)

  @Query("DELETE FROM events WHERE id = :id") suspend fun deleteEvent(id: String)

  @Query("DELETE FROM ops WHERE event = :id") suspend fun deleteOps(id: String)

  @Query("SELECT * FROM ops WHERE event = :id") fun opsFlow(id: String): Flow<List<OpRow>>

  @Query("SELECT * FROM ops WHERE event = :id ORDER BY lamport, author, seq")
  suspend fun opsOnce(id: String): List<OpRow>

  /**
   * ponytail: the whole log, loaded to answer one sync. A trip's log is hundreds of rows; add a
   * per-author range query if an event ever grows past ~10k ops.
   */
  @Query("SELECT * FROM ops") suspend fun allOps(): List<OpRow>

  /** IGNORE, not REPLACE: first version of an `(event, author, seq)` seen is the one we keep. */
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(rows: List<OpRow>)

  @Query("SELECT COUNT(*) FROM ops") suspend fun opCount(): Int

  @Query("SELECT event, author, seq FROM ops") suspend fun keys(): List<OpKey>

  @Query("SELECT id FROM events") suspend fun eventIds(): List<String>

  @Query("SELECT IFNULL(MAX(lamport), 0) FROM ops WHERE event = :id") suspend fun maxLamport(id: String): Long

  @Query("SELECT IFNULL(MAX(seq), 0) FROM ops WHERE event = :id AND author = :author")
  suspend fun maxSeq(id: String, author: String): Long
}

@Database(entities = [OpRow::class, EventRow::class], version = 1)
abstract class Db : RoomDatabase() {
  abstract fun dao(): OpDao
}

/**
 * The only thing the UI talks to. Appending an op and merging a peer's ops are the two writes;
 * everything else is a fold.
 */
class Repo(context: Context, signer: Signer) : OpStore {
  /**
   * A `var` for one reason: importing a key swaps the identity in place. Only ever written from
   * `SettingsScreen`, and only while no event has been joined, so no seq or authorship in the log
   * can be affected by the swap.
   */
  var signer: Signer = signer
  private val db =
    Room.databaseBuilder(context.applicationContext, Db::class.java, "bluecount.db").build()
  private val dao = db.dao()
  private val prefs = context.applicationContext.getSharedPreferences("bluecount", Context.MODE_PRIVATE)

  override val me: UserId get() = signer.id

  /** Remembered only to prefill the next event you join; the authority is the [Profile] op. */
  var nickname: String
    get() = prefs.getString("nick", "") ?: ""
    set(v) = prefs.edit().putString("nick", v).apply()

  /**
   * Quick picks offered by the currency fields. Purely a convenience list: free text still wins, so
   * any 3-letter code works whether or not it is in here. Not synced — this is one user's shortlist.
   */
  var currencies: List<String>
    get() = prefs.getString("currencies", null)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?.takeIf { it.isNotEmpty() } ?: DEFAULT_CURRENCIES
    set(v) = prefs.edit().putString("currencies", v.joinToString(",")).apply()

  /** Whether this phone beacons and listens for beacons (Wake.kt). The only off switch. */
  var wakeEnabled: Boolean
    get() = prefs.getBoolean("wake", true)
    set(v) = prefs.edit().putBoolean("wake", v).apply()

  /** When a beacon last woke us, to ignore the flood from a chip without first-match filtering. */
  var lastWake: Long
    get() = prefs.getLong("lastWake", 0)
    set(v) = prefs.edit().putLong("lastWake", v).apply()

  /** The battery-optimization exemption is asked for once and never again. */
  var batteryAsked: Boolean
    get() = prefs.getBoolean("batteryAsked", false)
    set(v) = prefs.edit().putBoolean("batteryAsked", v).apply()

  /** Set by the sync engine so the UI can show what is going on. */
  val events: Flow<List<EventRow>> = dao.events()

  fun state(eventId: String): Flow<EventState> =
    combine(dao.opsFlow(eventId), dao.event(eventId)) { ops, row ->
      fold(ops.map { it.toOp() }, row?.name ?: "", row?.currency ?: "")
    }

  suspend fun eventRow(id: String) = dao.eventOnce(id)

  suspend fun joinEvent(id: String, name: String, currency: String, nick: String) {
    if (dao.eventOnce(id) == null) dao.putEvent(EventRow(id, name, currency, System.currentTimeMillis()))
    nickname = nick
    append(id, Profile(nick))
  }

  suspend fun createEvent(name: String, currency: String, nick: String): String {
    val id = randomEventId()
    dao.putEvent(EventRow(id, name, currency, System.currentTimeMillis()))
    nickname = nick
    append(id, Genesis(name, currency))
    append(id, Profile(nick))
    return id
  }

  /**
   * Emergency backup: the log exactly as stored, one row per op. Edits and deletions are ops too,
   * so everything the UI hides is in here. The payload column is the signed JSON verbatim —
   * rebuilding it from decoded fields would no longer match [Op.sig].
   */
  suspend fun exportCsv(eventId: String): String = buildString {
    append("event,author,seq,lamport,payload,sig\n")
    for (r in dao.opsOnce(eventId)) {
      listOf(r.event, r.author, r.seq, r.lamport, r.payload.decodeToString(), r.sig.b64())
        .joinTo(this, ",") { "\"" + it.toString().replace("\"", "\"\"") + "\"" }
      append("\n")
    }
  }

  /** Local only — the others keep their copy, and rejoining by QR pulls it all back. */
  suspend fun leaveEvent(id: String) {
    dao.deleteOps(id)
    dao.deleteEvent(id)
  }

  /**
   * Appends one signed op: next seq for us in this event (spec §6), lamport one past the highest we
   * have seen so our edits sort after everything we already know about.
   */
  suspend fun append(eventId: String, build: (id: String) -> Payload): Op {
    val seq = dao.maxSeq(eventId, me) + 1
    val op = Op.create(signer, eventId, seq, dao.maxLamport(eventId) + 1, build("$me:$seq"))
    dao.insert(listOf(op.toRow()))
    onAppend?.invoke()
    return op
  }

  suspend fun append(eventId: String, payload: Payload) = append(eventId) { payload }

  /** Called after every local write so the sync engine can re-announce (spec §7). */
  var onAppend: (() -> Unit)? = null

  // -------------------------------------------------------------- sync support

  // The rules themselves live in Sync.kt as pure functions, so the tests exercise the same code
  // the app runs rather than a re-implementation that can quietly drift.

  override suspend fun clocks(): Map<String, Clock> = contiguousClocks(dao.keys(), dao.eventIds().toSet())

  override suspend fun opsFor(theirClocks: Map<String, Clock>): List<Op> =
    selectMissing(dao.allOps().map { it.toOp() }, theirClocks)

  override suspend fun merge(ops: List<Op>): Int {
    val good = acceptable(ops, dao.eventIds().toSet())
    if (good.isEmpty()) return 0
    // Count rows, not acceptable ops: IGNORE swallows the duplicates, and a file import re-offers
    // the whole history every time. "0 new" is the answer that tells the user the file was stale.
    val before = dao.opCount()
    dao.insert(good.map { it.toRow() })
    return dao.opCount() - before
  }

  /** The event as a file, for a chat app when nobody is near enough to sync. */
  suspend fun exportEvent(id: String): ByteArray = exportOps(dao.opsOnce(id).map { it.toOp() })
}

private fun randomEventId(): String = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.b64()

/** The shortlist a fresh install starts with. Editable in settings; the first one is the default. */
val DEFAULT_CURRENCIES = listOf("KZT", "RUB", "EGP", "USD", "EUR")
