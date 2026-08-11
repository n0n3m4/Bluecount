package com.bluecount

import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * The install-time identity (spec §1): a P-256 keypair generated on first launch, whose public key
 * *is* the user ID.
 *
 * It is an ordinary file in `filesDir`, not an Android Keystore key. Non-extractable hardware
 * protection is the wrong trade for a holiday's expenses: it made the identity unbackupable, so a
 * reinstall left every expense you ever wrote orphaned under an author nobody could sign as again.
 * A file can be exported and carried to the next phone (see `SettingsScreen`). It also rides
 * Android's own backup, which is the point — with the known cost that restoring a stale backup onto
 * a second phone gives two phones one identity, and equivocation handling then drops one side's ops.
 */
object Identity {
  fun keyFile(context: Context) = File(context.filesDir, "identity.key")

  /**
   * The file wins whenever it exists, which is also what makes an import stick for someone whose
   * old Keystore key is still on the phone.
   */
  fun signer(context: Context): Signer {
    val file = keyFile(context)
    if (file.exists())
      // Never fall through to generating a fresh one: silently becoming a different user would
      // orphan everything this phone has already signed.
      return parseIdentity(file.readText()) ?: error("identity.key is unreadable")
    keystoreSigner()?.let { return it }
    val fresh = newIdentity()
    file.writeText(fresh)
    return parseIdentity(fresh)!!
  }

  /**
   * ponytail: legacy identities from when the key was generated in the Android Keystore. It only
   * ever reads an alias that is already there — nothing creates one any more — so this whole
   * function goes away once no install predates the file key.
   */
  private fun keystoreSigner(): Signer? {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!ks.containsAlias(ALIAS)) return null
    return Signer(ks.getKey(ALIAS, null) as java.security.PrivateKey, ks.getCertificate(ALIAS).publicKey.encoded.b64())
  }

  private const val ALIAS = "bluecount-identity"
}
