package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.connect.DriveClient
import com.example.data.local.AppDatabase
import com.example.data.model.StudyNote
import com.example.data.repository.StudyPilotRepository
import com.example.data.sync.SyncEngine
import com.example.platform.KeyValueStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Google Drive storage: the Drive client, and a backup that restores exactly. */
class DriveBackupTest {
  private fun store() = object : KeyValueStore {
    val map = HashMap<String, Any?>()
    override fun getString(key: String, default: String?) = map[key] as? String ?: default
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun contains(key: String) = key in map
    override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
      override fun putString(key: String, value: String?) = apply { map[key] = value }
      override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
      override fun putLong(key: String, value: Long) = apply { map[key] = value }
      override fun remove(key: String) = apply { map.remove(key) }
      override fun apply() {}
    }
  }

  private fun db(name: String): Pair<AppDatabase, File> {
    val file = File.createTempFile(name, ".db").also { it.delete() }
    return AppDatabase.configure(Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)) to file
  }

  private class F(val name: String, val parent: String?, val folder: Boolean, var bytes: ByteArray = ByteArray(0))

  private fun reply(ex: HttpExchange, body: ByteArray) {
    ex.sendResponseHeaders(200, body.size.toLong())
    ex.responseBody.use { it.write(body) }
  }

  @Test
  fun driveClientFindsCreatesUploadsReplacesAndDownloads() = runBlocking<Unit> {
    // A tiny in-memory Drive: files by id with name, parent, folder flag and content.
    val files = LinkedHashMap<String, F>()
    var auth = ""
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/drive/v3/files") { ex ->
      auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
      val path = ex.requestURI.path.removePrefix("/drive/v3/files").trim('/')
      when {
        ex.requestMethod == "GET" && path.isEmpty() -> {
          val q = URLDecoder.decode(ex.requestURI.rawQuery.substringAfter("q=").substringBefore("&"), "UTF-8")
          val name = Regex("name = '([^']*)'").find(q)!!.groupValues[1]
          val parent = Regex("'([^']+)' in parents").find(q)?.groupValues?.get(1)
          val folder = "mimeType = 'application/vnd.google-apps.folder'" in q
          val hit = files.entries.firstOrNull { it.value.name == name && it.value.folder == folder && (parent == null || it.value.parent == parent) }
          reply(ex, JSONObject().put("files", JSONArray().apply { hit?.let { put(JSONObject().put("id", it.key)) } }).toString().toByteArray())
        }
        ex.requestMethod == "GET" -> reply(ex, files.getValue(path).bytes)
        else -> {
          val meta = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
          val id = "f${files.size + 1}"
          files[id] = F(meta.getString("name"), meta.optJSONArray("parents")?.optString(0), true)
          reply(ex, JSONObject().put("id", id).toString().toByteArray())
        }
      }
    }
    server.createContext("/upload/drive/v3/files") { ex ->
      val path = ex.requestURI.path.removePrefix("/upload/drive/v3/files").trim('/')
      val body = ex.requestBody.readBytes()
      if (path.isNotEmpty()) {
        // Replacing a file's content.
        files.getValue(path).bytes = body
        reply(ex, JSONObject().put("id", path).toString().toByteArray())
      } else {
        // Multipart: the metadata part, then the content part.
        val text = String(body, Charsets.ISO_8859_1)
        val boundary = text.lineSequence().first().trim()
        val parts = text.split(boundary).map { it.substringAfter("\r\n\r\n", "").removeSuffix("\r\n") }.filter { it.isNotEmpty() && it != "--" }
        val meta = JSONObject(parts[0])
        val id = "f${files.size + 1}"
        files[id] = F(meta.getString("name"), meta.getJSONArray("parents").getString(0), false, parts[1].toByteArray(Charsets.ISO_8859_1))
        reply(ex, JSONObject().put("id", id).toString().toByteArray())
      }
    }
    server.start()
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val drive = DriveClient({ "tok" }, "$base/drive/v3", "$base/upload/drive/v3")
      val root = drive.folder("StudyPilot", null)
      assertEquals(root, drive.folder("StudyPilot", null)) // found, not made twice
      val sub = drive.folder("Advanced IT Networks", root)
      val id = drive.put("Lecture 5.pdf", sub, "PDF-1".toByteArray(), "application/pdf")
      assertEquals("PDF-1", String(drive.download(id)))
      assertEquals(id, drive.put("Lecture 5.pdf", sub, "PDF-2".toByteArray(), "application/pdf", existingId = id))
      assertEquals("PDF-2", String(drive.download(id)))
      assertEquals(id, drive.find("Lecture 5.pdf", sub, folder = false))
      assertEquals("Bearer tok", auth)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun backupRestoresEverythingIntoAnEmptyApp() = runBlocking<Unit> {
    val (a, fa) = db("backup-src")
    val (b, fb) = db("backup-dst")
    try {
      val repoA = StudyPilotRepository(a.studyPilotDao())
      repoA.addSubject("Databases", "DB", "#123456", "u")
      repoA.saveNote(StudyNote(userId = "u", title = "Normal forms", heading = "3NF", body = "No transitive dependencies"))
      val engineA = SyncEngine(a, store())
      engineA.prepare()
      // What backupToDrive writes, and what comes back from Drive.
      val payload = JSONObject().put("changes", engineA.changesSince(0L, engineA.currentSeq(), excludeDevice = null))
      val restored = JSONObject(payload.toString())
      val engineB = SyncEngine(b, store())
      engineB.prepare()
      assertTrue(engineB.apply(restored.getJSONArray("changes"), fromDevice = "google-drive-backup") >= 2)
      assertEquals(listOf("Databases"), b.studyPilotDao().getSubjectsForUser("u").first().map { it.name })
      val note = b.studyPilotDao().getNotesForUser("u").first().single()
      assertEquals(Triple("Normal forms", "3NF", "No transitive dependencies"), Triple(note.title, note.heading, note.body))
    } finally {
      a.close(); b.close(); fa.delete(); fb.delete()
    }
  }
}
