package com.example.data.sync

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cloud chatbot keys shared between a paired phone and PC, so a key saved on one works on both.
 *
 * Keys only travel inside the phone-PC sync, encrypted with the pairing token both devices hold;
 * they never go to cloud backup. The app's settings register [export] and [import]; the newest
 * change (including removing a key) wins.
 */
object KeyShare {
  /** This device's keys as {updatedAt, provider, keys{}, models{}, customBaseUrl}, or null if none were ever set. */
  @Volatile var export: () -> JSONObject? = { null }

  /** Takes the other device's keys when they're newer. Returns true if anything changed here. */
  @Volatile var import: (JSONObject) -> Boolean = { false }

  /** [export], encrypted for the pairing [token]; null when there's nothing to send. */
  fun sealed(token: String): String? = runCatching { export()?.let { seal(it.toString(), token) } }.getOrNull()

  /** Opens and imports what the other device sent; ignored if it can't be opened. */
  fun receive(sealedText: String?, token: String): Boolean {
    if (sealedText.isNullOrBlank()) return false
    val json = runCatching { JSONObject(open(sealedText, token)) }.getOrNull() ?: return false
    return runCatching { import(json) }.getOrDefault(false)
  }

  private fun keyFor(token: String) = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("studypilot-keys|$token".toByteArray()), "AES")

  internal fun seal(plain: String, token: String): String {
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keyFor(token), GCMParameterSpec(128, iv)) }
    return (iv + cipher.doFinal(plain.toByteArray())).toByteString().base64()
  }

  internal fun open(sealedText: String, token: String): String {
    val bytes = sealedText.decodeBase64()?.toByteArray() ?: error("not base64")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(Cipher.DECRYPT_MODE, keyFor(token), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
    }
    return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
  }
}
