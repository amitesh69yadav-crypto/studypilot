package com.example.platform

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine

private const val FIRESTORE_DB_ID = "ai-studio-android-studypil-1b9a5a78-c2bd-40f4-9199-e1b41747056c"
private const val TAG = "FirestoreCloudStore"

/** Cloud backup through Firebase. Every call is a no-op when Firebase isn't set up (no google-services.json). */
class FirestoreCloudStore : CloudStore {

  private fun db() = FirebaseFirestore.getInstance(FIRESTORE_DB_ID)

  override fun currentUserId(): String? = try {
    FirebaseAuth.getInstance().currentUser?.uid
  } catch (_: Exception) {
    null
  }

  override fun set(path: String, data: Map<String, Any?>) {
    try {
      db().document(path).set(data).addOnFailureListener { Log.w(TAG, "Write failed for $path", it) }
    } catch (e: Exception) {
      Log.w(TAG, "Firestore unavailable", e)
    }
  }

  override fun delete(path: String) {
    try {
      db().document(path).delete()
    } catch (e: Exception) {
      Log.w(TAG, "Firestore unavailable", e)
    }
  }

  override fun deleteCollection(path: String) {
    try {
      db().collection(path).get().addOnSuccessListener { snap -> snap.documents.forEach { it.reference.delete() } }
    } catch (e: Exception) {
      Log.w(TAG, "Firestore unavailable", e)
    }
  }

  override suspend fun list(path: String): List<Map<String, Any?>> =
    db().collection(path).get().await().documents.map { it.data.orEmpty() }

  override suspend fun signInWithGoogleIdToken(idToken: String): CloudStore.CloudUser {
    val result = FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    val user = result.user ?: error("Google account information could not be retrieved.")
    return CloudStore.CloudUser(user.uid, user.email, user.displayName)
  }

  override fun signOut() {
    try {
      FirebaseAuth.getInstance().signOut()
    } catch (_: Exception) {}
  }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
  addOnSuccessListener { cont.resumeWith(Result.success(it)) }
  addOnFailureListener { cont.resumeWith(Result.failure(it)) }
  addOnCanceledListener { cont.cancel() }
}
