package com.bluecount

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/**
 * The install-time identity (spec §1): a P-256 keypair generated on first launch, whose public key
 * *is* the user ID.
 *
 * The private key lives in the Android Keystore and is not extractable, so it cannot be backed up
 * or moved to another phone — losing the device loses the identity. Expenses already published
 * survive in everyone else's log; you just can no longer sign new ones as that user.
 */
object Identity {
  private const val ALIAS = "bluecount-identity"

  private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

  val signer: Signer by lazy {
    if (!keyStore.containsAlias(ALIAS)) generate()
    val pub = keyStore.getCertificate(ALIAS).publicKey.encoded
    Signer(keyStore.getKey(ALIAS, null) as java.security.PrivateKey, pub.b64())
  }

  val me: UserId
    get() = signer.id

  private fun generate() {
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
      .apply {
        initialize(
          KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        )
      }
      .generateKeyPair()
  }
}
