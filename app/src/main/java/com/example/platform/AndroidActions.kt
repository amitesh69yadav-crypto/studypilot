package com.example.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.data.model.TimetableSession
import com.example.data.service.AlarmAndCalendarService
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** [PlatformActions] for an Activity (sign-in needs an Activity context). */
class AndroidActions(private val context: Context) : PlatformActions {

  override fun openUrl(url: String) {
    try {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
      showMessage("Could not open $url")
    }
  }

  override fun copyText(text: String, confirmation: String?) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("StudyPilot", text))
    confirmation?.let { showMessage(it) }
  }

  override fun showMessage(message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }

  override fun addToCalendar(session: TimetableSession) {
    val service = AlarmAndCalendarService(context)
    try {
      context.startActivity(service.createGoogleCalendarInsertIntent(session))
    } catch (_: Exception) {
      openUrl(service.getGoogleCalendarWebUrl(session))
    }
  }

  /** Saves into Downloads (via MediaStore on Android 10 and later), then says where. */
  override fun saveFile(fileName: String, mimeType: String, bytes: ByteArray) {
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
          put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
          put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
          put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          ?: throw IllegalStateException("No Downloads folder")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
      } else {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        java.io.File(dir, fileName).writeBytes(bytes)
      }
      showMessage("Saved $fileName to Downloads")
    } catch (e: Exception) {
      showMessage("Couldn't save the file: ${e.message ?: "unknown error"}")
    }
  }

  override val googleSignIn: (suspend () -> PlatformActions.GoogleAccount?) = suspend {
    // default_web_client_id is generated from google-services.json, which isn't checked in,
    // so look it up by name and fall back to the OAuth client from firebase-applet-config.json.
    @Suppress("DiscouragedApi")
    val generatedId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    val serverClientId = if (generatedId != 0) {
      context.getString(generatedId)
    } else {
      "924698065159-54lqb8uqm85brdd5hltkqtjgcnuhg9uv.apps.googleusercontent.com"
    }
    val request = GetCredentialRequest.Builder()
      .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
      .build()
    try {
      val credential = CredentialManager.create(context).getCredential(context = context, request = request).credential
      if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val token = GoogleIdTokenCredential.createFrom(credential.data)
        PlatformActions.GoogleAccount(token.idToken, token.displayName, token.id)
      } else {
        throw IllegalStateException("Google sign-in didn't return an account. Try again, or use email or guest mode.")
      }
    } catch (_: GetCredentialCancellationException) {
      null
    }
  }
}
