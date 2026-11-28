package com.example.data.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Signing in to Google on the PC, for Drive storage and Classroom. Read-only Classroom access and
 * Drive access limited to the files this app creates (drive.file).
 */
interface GoogleAuth {
  /** The signed-in account's email, or null. */
  val email: String?

  /** Opens Google's sign-in page in the browser and waits for the answer. Returns the email. */
  suspend fun signIn(clientId: String, clientSecret: String): String

  /** A current access token, refreshed when needed; throws when not signed in. */
  suspend fun accessToken(): String

  fun signOut()

  companion object {
    val SCOPES = listOf(
      "openid",
      "email",
      "https://www.googleapis.com/auth/drive.file",
      "https://www.googleapis.com/auth/classroom.courses.readonly",
      "https://www.googleapis.com/auth/classroom.coursework.me.readonly",
      "https://www.googleapis.com/auth/classroom.courseworkmaterials.readonly",
      "https://www.googleapis.com/auth/classroom.announcements.readonly"
    )
  }
}

/**
 * Google Drive, limited to what StudyPilot creates: a "StudyPilot" folder with the data backup and a
 * folder per subject holding the course files.
 */
class DriveClient(
  private val token: suspend () -> String,
  /** Tests point these at a local fake. */
  private val api: String = "https://www.googleapis.com/drive/v3",
  private val upload: String = "https://www.googleapis.com/upload/drive/v3"
) {
  private val json = "application/json; charset=utf-8".toMediaType()

  private suspend fun call(request: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
    LmsHttp.client.newCall(request.header("Authorization", "Bearer ${token()}").build()).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      if (resp.code == 401 || resp.code == 403) throw LmsException("Google Drive refused access (${resp.code}). Sign in to Google again.")
      if (!resp.isSuccessful) throw LmsException("Google Drive answered ${resp.code}.")
      if (body.isBlank()) JSONObject() else JSONObject(body)
    }
  }

  private fun q(text: String) = URLEncoder.encode(text, "UTF-8")
  private fun quote(name: String) = name.replace("\\", "\\\\").replace("'", "\\'")

  /** The id of [name] inside [parent] (a folder, or the file when [folder] is false), or null. */
  suspend fun find(name: String, parent: String?, folder: Boolean): String? {
    val clauses = mutableListOf("name = '${quote(name)}'", "trashed = false")
    clauses += if (folder) "mimeType = 'application/vnd.google-apps.folder'" else "mimeType != 'application/vnd.google-apps.folder'"
    if (parent != null) clauses += "'$parent' in parents"
    val res = call(Request.Builder().url("$api/files?q=${q(clauses.joinToString(" and "))}&fields=files(id)&pageSize=1"))
    return res.optJSONArray("files")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
  }

  /** The folder [name] inside [parent], created when it isn't there. */
  suspend fun folder(name: String, parent: String?): String = find(name, parent, folder = true) ?: run {
    val meta = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
    if (parent != null) meta.put("parents", org.json.JSONArray().put(parent))
    call(Request.Builder().url("$api/files?fields=id").post(meta.toString().toRequestBody(json))).getString("id")
  }

  /** Uploads [bytes] as [name] in [parent], replacing the content of [existingId] when given. Returns the id. */
  suspend fun put(name: String, parent: String, bytes: ByteArray, mime: String, existingId: String? = null): String {
    val body = bytes.toRequestBody(mime.toMediaType())
    if (existingId != null) {
      return call(Request.Builder().url("$upload/files/$existingId?uploadType=media&fields=id").patch(body)).getString("id")
    }
    val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parent))
    val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
      .addPart(meta.toString().toRequestBody(json))
      .addPart(body)
      .build()
    return call(Request.Builder().url("$upload/files?uploadType=multipart&fields=id").post(multipart)).getString("id")
  }

  suspend fun download(id: String): ByteArray = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("$api/files/$id?alt=media").header("Authorization", "Bearer ${token()}").build()
    LmsHttp.client.newCall(request).execute().use { resp ->
      if (!resp.isSuccessful) throw LmsException("Google Drive answered ${resp.code}.")
      resp.body?.bytes() ?: ByteArray(0)
    }
  }

  companion object {
    fun mimeFor(type: String) = when (type) {
      "pdf" -> "application/pdf"
      "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
      "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      else -> "application/octet-stream"
    }
  }
}
