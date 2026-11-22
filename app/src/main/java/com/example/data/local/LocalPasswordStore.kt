package com.example.data.local

import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2 password hashes for on-device email profiles.
 *
 * Email profiles never leave the device, so this only stops someone else using the same
 * phone from opening another person's profile. Stored as "pbkdf2$iterations$salt$hash".
 */
class LocalPasswordStore(private val prefs: SharedPreferences) {

  fun hasPassword(userId: String): Boolean = prefs.contains(key(userId))

  fun setPassword(userId: String, password: String) {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val hash = derive(password, salt, ITERATIONS)
    prefs.edit().putString(key(userId), "pbkdf2$$ITERATIONS$${encode(salt)}$${encode(hash)}").apply()
  }

  fun verify(userId: String, password: String): Boolean {
    val parts = prefs.getString(key(userId), null)?.split("$") ?: return false
    if (parts.size != 4 || parts[0] != "pbkdf2") return false
    val iterations = parts[1].toIntOrNull() ?: return false
    val salt = decode(parts[2])
    val expected = decode(parts[3])
    return MessageDigest.isEqual(expected, derive(password, salt, iterations))
  }

  fun remove(userId: String) {
    prefs.edit().remove(key(userId)).apply()
  }

  private fun key(userId: String) = "password_hash_$userId"

  private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }

  private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
  private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

  private companion object {
    // Hashing runs on the sign-in tap; this keeps it well under 100 ms on a mid-range phone.
    const val ITERATIONS = 50_000
  }
}
