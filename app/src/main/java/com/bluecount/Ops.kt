package com.bluecount

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
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

/** Short, human-comparable form of a user ID, for debug rows and disambiguating identical nicks. */
fun UserId.shortId(): String = take(8)

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
  /** Cash handed over to settle up. Same maths, different wording in the UI. */
  REIMBURSEMENT,
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
  val payer: UserId = "",
  val mode: SplitMode = SplitMode.EQUAL,
  val shares: Map<UserId, Long> = emptyMap(),
  val kind: Kind = Kind.EXPENSE,
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
