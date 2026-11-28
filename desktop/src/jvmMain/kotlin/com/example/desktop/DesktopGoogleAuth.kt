package com.example.desktop

import com.example.data.connect.GoogleAuth
import com.example.data.connect.LmsException
import com.example.data.connect.LmsHttp
import com.example.platform.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.awt.Desktop
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google sign-in for the desktop app: Google's page opens in the browser, and the answer comes back
 * to a one-time address on this PC (127.0.0.1). Uses PKCE. The refresh token stays in this PC's
 * settings, so the student signs in once.
 */
class DesktopGoogleAuth(private val prefs: KeyValueStore) : GoogleAuth {

  override val email: String? get() = prefs.getString("google_email", null)?.takeIf { prefs.getString("google_refresh", null) != null }

  @Volatile private var access: String? = null
  @Volatile private var accessUntil = 0L

  override suspend fun signIn(clientId: String, clientSecret: String): String = withContext(Dispatchers.IO) {
    val verifier = randomUrlSafe(48)
    val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    val state = randomUrlSafe(16)
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      server.soTimeout = 5 * 60_000
      val redirect = "http://127.0.0.1:${server.localPort}"
      val url = "https://accounts.google.com/o/oauth2/v2/auth?" + listOf(
        "client_id" to clientId,
        "redirect_uri" to redirect,
        "response_type" to "code",
        "scope" to GoogleAuth.SCOPES.joinToString(" "),
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "access_type" to "offline",
        "prompt" to "consent",
        "state" to state
      ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
      Desktop.getDesktop().browse(URI(url))

      // Wait for Google to send the browser back here.
      val params = server.accept().use { socket ->
        val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
        val query = requestLine.substringAfter("?", "").substringBefore(" ")
        val found = query.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        val ok = found["code"] != null && found["state"] == state
        val page = if (ok) "<h2>Signed in to StudyPilot.</h2><p>You can close this tab and go back to the app.</p>"
        else "<h2>Sign-in didn't finish.</h2><p>Go back to StudyPilot and try again.</p>"
        val html = "<!doctype html><html><body style=\"font-family:sans-serif;padding:40px\">$page</body></html>"
        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html").toByteArray())
        found
      }
      if (params["state"] != state) throw LmsException("Sign-in was interrupted. Try again.")
      val code = params["code"] ?: throw LmsException(if (params["error"] == "access_denied") "You didn't allow access." else "Google didn't sign you in (${params["error"] ?: "no code"}).")

      val tokens = token(FormBody.Builder()
        .add("code", code).add("client_id", clientId).add("client_secret", clientSecret)
        .add("redirect_uri", redirect).add("grant_type", "authorization_code").add("code_verifier", verifier).build())
      val refresh = tokens.optString("refresh_token").ifBlank { throw LmsException("Google didn't grant lasting access. Try again.") }
      val mail = idTokenEmail(tokens.optString("id_token")) ?: "Google account"
      prefs.edit {
        putString("google_client_id", clientId)
        putString("google_client_secret", clientSecret)
        putString("google_refresh", refresh)
        putString("google_email", mail)
      }
      remember(tokens)
      mail
    }
  }

  override suspend fun accessToken(): String {
    access?.let { if (System.currentTimeMillis() < accessUntil) return it }
    val refresh = prefs.getString("google_refresh", null) ?: throw LmsException("Sign in to Google first (Settings > Storage).")
    val tokens = withContext(Dispatchers.IO) {
      token(FormBody.Builder()
        .add("refresh_token", refresh)
        .add("client_id", prefs.getString("google_client_id", "").orEmpty())
        .add("client_secret", prefs.getString("google_client_secret", "").orEmpty())
        .add("grant_type", "refresh_token").build())
    }
    return remember(tokens)
  }

  override fun signOut() {
    access = null
    prefs.edit { remove("google_refresh"); remove("google_email") }
  }

  private fun remember(tokens: JSONObject): String {
    val token = tokens.getString("access_token")
    access = token
    accessUntil = System.currentTimeMillis() + (tokens.optLong("expires_in", 3600) - 60) * 1000
    return token
  }

  private fun token(form: FormBody): JSONObject {
    val request = Request.Builder().url("https://oauth2.googleapis.com/token").post(form).build()
    LmsHttp.client.newCall(request).execute().use { resp ->
      val body = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
      if (!resp.isSuccessful) {
        val why = body.optString("error_description", body.optString("error", "error ${resp.code}"))
        if (body.optString("error") == "invalid_grant") signOut()
        throw LmsException("Google sign-in failed: $why")
      }
      return body
    }
  }

  /** The email inside Google's ID token (its middle part is base64 JSON). */
  private fun idTokenEmail(idToken: String): String? = runCatching {
    JSONObject(String(Base64.getUrlDecoder().decode(idToken.split('.')[1]))).optString("email").takeIf { it.isNotBlank() }
  }.getOrNull()

  private fun randomUrlSafe(bytes: Int) = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}
