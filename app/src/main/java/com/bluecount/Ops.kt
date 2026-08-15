package com.bluecount

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.io.InputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The whole data model: an append-only log of signed operations. Every screen in the app is a pure
 * fold over these (see [Ledger]).
 *
 * Deliberately free of Android imports so the protocol can be unit-tested on the JVM.
 */

// ---------------------------------------------------------------- identity

private val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val B64D: Base64.Decoder = Base64.getUrlDecoder()

fun ByteArray.b64(): String = B64.encodeToString(this)

fun String.unb64(): ByteArray = B64D.decode(this)

/** A user ID is the base64url of the X.509 SubjectPublicKeyInfo of their P-256 key. */
typealias UserId = String

/**
 * Short, human-comparable form of a user ID, for debug rows and disambiguating identical nicks.
 * Taken from the *end*: an X.509 SubjectPublicKeyInfo starts with a 27-byte DER header that is
 * identical for every P-256 key ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"), so a prefix is a
 * constant, not an id. NearbyTransport also uses this as the advertised endpoint name and dials on
 * `myName < theirName`, so a constant here means no phone ever dials and sync silently never runs.
 */
fun UserId.shortId(): String = takeLast(8)

/** Holds the private half. The app builds one from the Android Keystore; tests from a plain keypair. */
class Signer(private val key: PrivateKey, val id: UserId) {
  fun sign(data: ByteArray): ByteArray =
    Signature.getInstance("SHA256withECDSA").run {
      initSign(key)
      update(data)
      sign()
    }
}

private val ecKeyFactory = KeyFactory.getInstance("EC")

/**
 * A stored identity: two base64url lines, the X.509 public half then the PKCS#8 private one. Both
 * halves, because the JDK will not derive the public point from an EC private key and doing that
 * arithmetic by hand is a lot of code to save one line of file.
 *
 * This exact text is what sits in `filesDir` and what leaves the phone as an export.
 *
 * ponytail: unwrapped. If a plaintext private key crossing a share sheet ever matters, wrap it in
 * PBKDF2 + AES-GCM and ask for a passphrase on both ends.
 */
fun newIdentity(): String {
  val kp =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  return kp.public.encoded.b64() + "\n" + kp.private.encoded.b64()
}

/**
 * Reads one back. Null for anything malformed — this is a trust boundary, and the file may have
 * come from a share sheet, a chat app or a text editor.
 *
 * The probe signature is the point: a file pairing one key's public half with another's private
 * half parses fine and then signs ops that no peer on earth can verify. Ops are immutable, so that
 * is not a mistake anyone can take back.
 */
fun parseIdentity(text: String): Signer? =
  try {
    val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    require(lines.size == 2)
    val (id, priv) = lines
    val signer = Signer(ecKeyFactory.generatePrivate(PKCS8EncodedKeySpec(priv.unb64())), id)
    val probe = id.encodeToByteArray()
    signer.takeIf { verify(id, probe, it.sign(probe)) }
  } catch (_: Exception) {
    null
  }

/** False for anything malformed, not just for a wrong signature — this is a trust boundary. */
fun verify(author: UserId, data: ByteArray, sig: ByteArray): Boolean =
  try {
    Signature.getInstance("SHA256withECDSA").run {
      initVerify(ecKeyFactory.generatePublic(X509EncodedKeySpec(author.unb64())))
      update(data)
      verify(sig)
    }
  } catch (_: Exception) {
    false
  }

// ---------------------------------------------------------------- payloads

enum class SplitMode {
  /** Everyone in [Put.shares] pays the same (weights ignored). */
  EQUAL,
  /** [Put.shares] are relative weights: 2 means "twice as much as a 1". */
  SHARES,
  /** [Put.shares] are literal cents and must sum to [Put.cents]. */
  EXACT,
}

enum class Kind {
  EXPENSE,
  /**
   * Cash handed over to settle up, in part or in full. Always one-to-one. Same maths as an expense
   * with a single participant; "Payback" in the UI. The wire name predates that wording and is
   * deliberately not renamed — an enum entry is part of the signed payload, so an old op carrying
   * `REIMBURSEMENT` would stop decoding.
   */
  REIMBURSEMENT,
  /**
   * Net-zero swap between two people: [Put.cents] of [Put.currency] one way, [Put.toCents] of
   * [Put.toCurrency] back. Manual cross-currency netting — there is no rate table anywhere, the
   * rate is exactly `toCents / cents` of this one op and applies to nothing else.
   */
  CONVERSION,
}

@Serializable
sealed interface Payload

/** Event metadata. Written once by whoever created the event. */
@Serializable
@SerialName("g")
data class Genesis(val name: String, val currency: String) : Payload

/** Nickname claim. Publishing one is also what makes you a member of the event. */
@Serializable @SerialName("p") data class Profile(val nick: String) : Payload

/**
 * Create, edit *and* delete an expense — spec §9: every mutation is a new op, nothing is ever
 * rewritten. [id] is `"<author>:<seq>"` of the op that first created the expense.
 */
@Serializable
@SerialName("x")
data class Put(
  val id: String,
  val deleted: Boolean = false,
  val title: String = "",
  val cents: Long = 0,
  /** Epoch day, chosen by the user — device clocks disagree, so this is never used for ordering. */
  val date: Long = 0,
  /**
   * UTC millis, the moment the expense happened, so a row can be lined up against a card statement.
   * Additive: 0 means an op from a build before this field existed, which is why [date] stays an
   * epoch day rather than becoming an instant — reinterpreting it would move every expense already
   * on a phone to 1970. New ops write both, so an older build still reads the right day.
   *
   * Like [date], never used for ordering: it is the user's wall clock, not a causal one.
   */
  val at: Long = 0,
  val payer: UserId = "",
  val mode: SplitMode = SplitMode.EQUAL,
  val shares: Map<UserId, Long> = emptyMap(),
  val kind: Kind = Kind.EXPENSE,
  /**
   * Blank means "the event default from [Genesis]", resolved in `fold()` and never stored resolved,
   * so ops written before currencies existed mean exactly what they always meant.
   *
   * ponytail: a build predating this field folds a foreign-currency Put into the event currency
   * (`ignoreUnknownKeys`), and one predating [Kind.CONVERSION] drops such a Put entirely (unknown
   * enum → payload does not decode → stored and relayed but not folded). The second degrades
   * safely, the first mis-sums. Acceptable pre-release; upgrade path is a version field in Genesis.
   */
  val currency: String = "",
  /** [Kind.CONVERSION] only: what the single participant hands back, in [toCurrency]. */
  val toCents: Long = 0,
  val toCurrency: String = "",
  /**
   * VAT or service charge, in basis points (1% = 100), 0 for none. Display only, and deliberately
   * so: [cents] and [shares] are always the VAT-*inclusive* numbers — the amount that actually
   * changed hands — so a build predating this field folds exactly the same balances. It just cannot
   * show the subtotal split back out. Storing the net instead would have made this the one field
   * whose absence changes what somebody owes.
   */
  val vatBp: Long = 0,
) : Payload

/** Tolerant on purpose: an op we cannot parse is still stored and relayed, just not folded. */
private val json = Json {
  classDiscriminator = "t"
  ignoreUnknownKeys = true
  encodeDefaults = true
}

fun Payload.encode(): ByteArray = json.encodeToString(Payload.serializer(), this).toByteArray()

fun decodePayload(bytes: ByteArray): Payload? =
  try {
    json.decodeFromString(Payload.serializer(), bytes.decodeToString())
  } catch (_: Exception) {
    null
  }

// ---------------------------------------------------------------- ops

/**
 * @param seq per `(event, author)`, starting at 1 and contiguous — spec §6.
 * @param lamport `max(seen in this event) + 1`. The spec's per-author [seq] cannot order two
 *   authors against each other, so without this "last edit wins" resolves differently on different
 *   devices. Total order is `(lamport, author, seq)`.
 * @param payload kept exactly as received and never re-serialized, which is what makes the
 *   signature verifiable without a canonical JSON form.
 */
class Op(
  val event: String,
  val author: UserId,
  val seq: Long,
  val lamport: Long,
  val payload: ByteArray,
  val sig: ByteArray,
) {
  val key: String
    get() = "$author:$seq"

  /** Includes [event], so an op cannot be replayed into a different event. */
  fun signedBytes(): ByteArray = buildMsg {
    writeChunk(event.toByteArray())
    writeChunk(author.toByteArray())
    writeLong(seq)
    writeLong(lamport)
    writeChunk(payload)
  }

  fun verified(): Boolean = seq >= 1 && verify(author, signedBytes(), sig)

  companion object {
    fun create(signer: Signer, event: String, seq: Long, lamport: Long, payload: Payload): Op {
      val bytes = payload.encode()
      val unsigned = Op(event, signer.id, seq, lamport, bytes, ByteArray(0))
      return Op(event, signer.id, seq, lamport, bytes, signer.sign(unsigned.signedBytes()))
    }
  }
}

// ---------------------------------------------------------------- wire format

/** Per-event `author -> highest contiguous seq held`. The peer sends everything above it. */
typealias Clock = Map<UserId, Long>

sealed interface Msg

/** Opening move from both sides at once; there is no client and no server. */
data class Hello(val id: UserId, val have: Map<String, Clock>) : Msg

/** @param more false on the last batch, which lets the peer know it can hang up. */
data class OpBatch(val ops: List<Op>, val more: Boolean) : Msg

/** Nearby caps a BYTES payload at 32 KiB; stay under it with room for framing. */
const val MAX_BATCH_BYTES = 24 * 1024

private const val MAX_CHUNK = 1 shl 20

private fun DataOutputStream.writeChunk(b: ByteArray) {
  writeInt(b.size)
  write(b)
}

private fun DataInputStream.readChunk(): ByteArray {
  val n = readInt()
  require(n in 0..MAX_CHUNK) { "chunk size $n" }
  return ByteArray(n).also { readFully(it) }
}

/** A hostile peer can claim a billion entries; refuse to preallocate for it. */
private fun DataInputStream.readCount(): Int = readInt().also { require(it in 0..100_000) { "count $it" } }

private fun buildMsg(body: DataOutputStream.() -> Unit): ByteArray {
  val out = ByteArrayOutputStream()
  DataOutputStream(out).use(body)
  return out.toByteArray()
}

fun Msg.encode(): ByteArray = buildMsg {
  when (this@encode) {
    is Hello -> {
      writeByte(1)
      writeChunk(id.toByteArray())
      writeInt(have.size)
      for ((event, clock) in have) {
        writeChunk(event.toByteArray())
        writeInt(clock.size)
        for ((author, seq) in clock) {
          writeChunk(author.toByteArray())
          writeLong(seq)
        }
      }
    }
    is OpBatch -> {
      writeByte(2)
      writeBoolean(more)
      writeInt(ops.size)
      for (op in ops) {
        writeChunk(op.event.toByteArray())
        writeChunk(op.author.toByteArray())
        writeLong(op.seq)
        writeLong(op.lamport)
        writeChunk(op.payload)
        writeChunk(op.sig)
      }
    }
  }
}

/** Null for anything we cannot parse — the bytes came off a radio from an unauthenticated peer. */
fun decodeMsg(bytes: ByteArray): Msg? =
  try {
    DataInputStream(ByteArrayInputStream(bytes)).use { i ->
      when (i.readByte().toInt()) {
        1 -> {
          val id = i.readChunk().decodeToString()
          val have = buildMap {
            repeat(i.readCount()) {
              val event = i.readChunk().decodeToString()
              val clock = buildMap { repeat(i.readCount()) { put(i.readChunk().decodeToString(), i.readLong()) } }
              put(event, clock)
            }
          }
          Hello(id, have)
        }
        2 -> {
          val more = i.readBoolean()
          OpBatch(
            List(i.readCount()) {
              Op(
                event = i.readChunk().decodeToString(),
                author = i.readChunk().decodeToString(),
                seq = i.readLong(),
                lamport = i.readLong(),
                payload = i.readChunk(),
                sig = i.readChunk(),
              )
            },
            more,
          )
        }
        else -> null
      }
    }
  } catch (_: Exception) {
    null
  }

// ---------------------------------------------------------------- file format

/**
 * Ceiling on anything read from a file. A holiday's log is tens of kilobytes; this is only here so
 * that a hostile file cannot decide how much heap we allocate.
 */
const val MAX_FILE_BYTES = 16 * 1024 * 1024

/**
 * Read the whole stream, or null if it holds more than [max]. Both ends of the import path are
 * untrusted — the file came from whoever was in the chat, and a deflate stream can inflate to
 * gigabytes from a few hundred bytes, so the cap has to be enforced *while* reading.
 */
fun readCapped(input: InputStream, max: Int = MAX_FILE_BYTES): ByteArray? {
  val out = ByteArrayOutputStream()
  val buf = ByteArray(8192)
  while (true) {
    val n = input.read(buf)
    if (n < 0) return out.toByteArray()
    if (out.size() + n > max) return null
    out.write(buf, 0, n)
  }
}

/**
 * The event as a file, for carrying through a chat app when nobody is near enough to sync.
 *
 * It is exactly a sync batch — same codec, same signatures, same `acceptable()` on the way in — so
 * there is no second protocol to keep correct. Whole history rather than a delta: the sender has no
 * idea what the reader already holds, and re-merging what they have is a no-op anyway.
 *
 * Deflated because a chat upload is metered and the log is JSON payloads and base64 keys, which
 * compress to roughly a third. Only the file, never the radio: [MAX_BATCH_BYTES] already keeps
 * Nearby batches small, and compressing the wire would stop a new build talking to an installed one.
 */
fun exportOps(ops: List<Op>): ByteArray {
  val out = ByteArrayOutputStream()
  DeflaterOutputStream(out).use { it.write(OpBatch(ops, more = false).encode()) }
  return out.toByteArray()
}

/** Empty for anything that is not one of our files — that is the whole error path. */
fun importOps(bytes: ByteArray): List<Op> =
  try {
    val raw = InflaterInputStream(ByteArrayInputStream(bytes)).use { readCapped(it) }
    (raw?.let { decodeMsg(it) } as? OpBatch)?.ops.orEmpty()
  } catch (_: Exception) {
    emptyList()
  }
