package com.bluecount

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** The Android Keystore is unavailable off-device, so tests use a plain in-memory P-256 keypair. */
fun testSigner(): Signer {
  val kp =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  return Signer(kp.private, kp.public.encoded.b64())
}

/** Minimal stand-in for the app's op-appending logic, so tests can build a log in a few lines. */
class TestLog(val event: String = "trip") {
  val ops = mutableListOf<Op>()
  private val seqs = mutableMapOf<UserId, Long>()
  private var lamport = 0L

  fun add(signer: Signer, payload: (id: String) -> Payload): Op {
    val seq = (seqs[signer.id] ?: 0L) + 1
    seqs[signer.id] = seq
    lamport++
    return Op.create(signer, event, seq, lamport, payload("${signer.id}:$seq")).also { ops += it }
  }

  fun add(signer: Signer, payload: Payload): Op = add(signer) { payload }
}
