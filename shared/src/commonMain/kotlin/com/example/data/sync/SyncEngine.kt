package com.example.data.sync

import androidx.room.PooledConnection
import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteStatement
import com.example.data.local.AppDatabase
import com.example.platform.KeyValueStore
import com.example.platform.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps two copies of the database (phone and PC) in step.
 *
 * Triggers record every insert, update and delete in `sync_ids`: one row per record with a global
 * id (`uid`), when it last changed, a local change number (`seq`) and which device made the change
 * (`src`). A peer asks for everything after the last change number it saw. The newest change to a
 * record wins. Entity tables stay exactly as Room defines them; ids that point at other records are
 * sent as uids and mapped back to local ids on arrival.
 */
class SyncEngine(private val db: AppDatabase, private val authPrefs: KeyValueStore) {

  private val mutex = Mutex()
  private var prepared = false

  /** This database's id. A new id means the database was recreated, so peers start over. */
  lateinit var deviceId: String
    private set

  private class Table(val name: String, val refs: Map<String, String> = emptyMap())

  /** Parents before children, so references resolve when changes are applied in order. */
  private val tables = listOf(
    Table("subjects"),
    Table("imported_chats", mapOf("subjectId" to "subjects")),
    Table("study_topics", mapOf("subjectId" to "subjects")),
    Table("quiz_questions", mapOf("topicId" to "study_topics", "sourceConversationId" to "imported_chats")),
    Table("quiz_attempts"),
    Table("timetable_sessions", mapOf("subjectId" to "subjects")),
    Table("study_tasks"),
    Table("extracted_concepts", mapOf("sourceChatId" to "imported_chats", "subjectId" to "subjects")),
    Table("tutor_sessions", mapOf("subjectId" to "subjects")),
    Table("tutor_messages", mapOf("sessionId" to "tutor_sessions")),
    Table("schedule_formats")
  )
  private val tableByName = tables.associateBy { it.name }
  val tableNames: Array<String> get() = tables.map { it.name }.toTypedArray()

  /** Creates the sync tables and triggers (once per database) and tracks rows made before them. */
  suspend fun prepare() = mutex.withLock { prepareLocked() }

  private suspend fun prepareLocked() {
    if (prepared) return
    db.useWriterConnection { conn ->
      conn.immediate {
        exec("CREATE TABLE IF NOT EXISTS sync_kv (k TEXT PRIMARY KEY NOT NULL, v TEXT)")
        exec(
          "CREATE TABLE IF NOT EXISTS sync_ids (tbl TEXT NOT NULL, local_id INTEGER NOT NULL, uid TEXT NOT NULL, " +
            "updated_at INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
            "src TEXT NOT NULL DEFAULT '', PRIMARY KEY (tbl, local_id))"
        )
        exec("CREATE UNIQUE INDEX IF NOT EXISTS sync_ids_uid ON sync_ids (uid)")
        exec("CREATE INDEX IF NOT EXISTS sync_ids_seq ON sync_ids (seq)")
        exec("INSERT OR IGNORE INTO sync_kv (k, v) VALUES ('device', lower(hex(randomblob(8))))")
        exec("INSERT OR REPLACE INTO sync_kv (k, v) VALUES ('applying', '0')")
        for (t in tables) {
          val n = t.name
          exec(
            "CREATE TRIGGER IF NOT EXISTS sync_ins_$n AFTER INSERT ON $n WHEN $NOT_APPLYING BEGIN " +
              "INSERT INTO sync_ids (tbl, local_id, uid) SELECT '$n', NEW.id, lower(hex(randomblob(16))) " +
              "WHERE NOT EXISTS (SELECT 1 FROM sync_ids WHERE tbl = '$n' AND local_id = NEW.id); " +
              "UPDATE sync_ids SET $TOUCH, deleted = 0 WHERE tbl = '$n' AND local_id = NEW.id; END"
          )
          exec(
            "CREATE TRIGGER IF NOT EXISTS sync_upd_$n AFTER UPDATE ON $n WHEN $NOT_APPLYING BEGIN " +
              "INSERT INTO sync_ids (tbl, local_id, uid) SELECT '$n', NEW.id, lower(hex(randomblob(16))) " +
              "WHERE NOT EXISTS (SELECT 1 FROM sync_ids WHERE tbl = '$n' AND local_id = NEW.id); " +
              "UPDATE sync_ids SET $TOUCH, deleted = 0 WHERE tbl = '$n' AND local_id = NEW.id; END"
          )
          exec(
            "CREATE TRIGGER IF NOT EXISTS sync_del_$n AFTER DELETE ON $n WHEN $NOT_APPLYING BEGIN " +
              "UPDATE sync_ids SET $TOUCH, deleted = 1 WHERE tbl = '$n' AND local_id = OLD.id; END"
          )
          // Room's "INSERT OR REPLACE" / "UPDATE OR ABORT" override any conflict clause inside a trigger,
          // so the triggers avoid conflicts instead of relying on OR IGNORE.
          // Rows from before sync existed (or from before the triggers were added).
          exec(
            "INSERT INTO sync_ids (tbl, local_id, uid, updated_at, seq, src) " +
              "SELECT '$n', id, lower(hex(randomblob(16))), $NOW_MS, (SELECT IFNULL(MAX(seq), 0) FROM sync_ids) + 1, " +
              "(SELECT v FROM sync_kv WHERE k = 'device') FROM $n WHERE id NOT IN (SELECT local_id FROM sync_ids WHERE tbl = '$n')"
          )
        }
        deviceId = query("SELECT v FROM sync_kv WHERE k = 'device'") { it.getText(0) }.first()
      }
    }
    prepared = true
  }

  /** Global ids of [localIds] in [table] (rows not tracked yet are left out). */
  suspend fun stableIds(table: String, localIds: Collection<Long>): Map<Long, String> {
    prepare()
    val wanted = localIds.toHashSet()
    return db.useReaderConnection { conn ->
      conn.query("SELECT local_id, uid FROM sync_ids WHERE tbl = ?", { bindText(1, table) }) { it.getLong(0) to it.getText(1) }
    }.filter { it.first in wanted }.toMap()
  }

  /** Latest local change number. */
  suspend fun currentSeq(): Long {
    prepare()
    return db.useReaderConnection { conn -> conn.query("SELECT IFNULL(MAX(seq), 0) FROM sync_ids") { it.getLong(0) }.first() }
  }

  /**
   * Changes numbered after [since] and up to [upTo], leaving out ones that came from [excludeDevice]
   * (it already has them).
   */
  suspend fun changesSince(since: Long, upTo: Long, excludeDevice: String?): JSONArray = mutex.withLock {
    prepareLocked()
    db.useReaderConnection { conn ->
      val out = JSONArray()
      val rows = conn.query(
        "SELECT tbl, local_id, uid, updated_at, deleted FROM sync_ids WHERE seq > ? AND seq <= ? AND src != ? ORDER BY seq",
        { bindLong(1, since); bindLong(2, upTo); bindText(3, excludeDevice ?: "") }
      ) { Change(it.getText(0), it.getLong(1), it.getText(2), it.getLong(3), it.getLong(4) == 1L) }
      // Parents first, so the receiver can resolve references.
      val order = tables.withIndex().associate { it.value.name to it.index }
      for (c in rows.sortedBy { order[it.tbl] ?: Int.MAX_VALUE }) {
        val table = tableByName[c.tbl] ?: continue
        val change = JSONObject().put("t", c.tbl).put("u", c.uid).put("at", c.updatedAt)
        if (c.deleted) {
          out.put(change.put("d", true))
          continue
        }
        val row = conn.readRow(c.tbl, c.localId) ?: continue
        val refs = JSONObject()
        for ((column, parent) in table.refs) {
          val parentId = row.optLong(column, 0L)
          if (parentId > 0L) conn.uidOf(parent, parentId)?.let { refs.put(column, it) }
        }
        row.remove("id")
        out.put(change.put("row", row).put("refs", refs))
      }
      out
    }
  }

  /** Applies a peer's [changes]; the newest version of each record wins. Returns how many were applied. */
  suspend fun apply(changes: JSONArray, fromDevice: String): Int = mutex.withLock {
    prepareLocked()
    if (changes.length() == 0) return@withLock 0
    var applied = 0
    db.useWriterConnection { conn ->
      conn.immediate {
        exec("UPDATE sync_kv SET v = '1' WHERE k = 'applying'")
        try {
          val columnsByTable = HashMap<String, Set<String>>()
          for (i in 0 until changes.length()) {
            val change = changes.getJSONObject(i)
            val tbl = change.getString("t")
            val table = tableByName[tbl] ?: continue
            val columns = columnsByTable.getOrPut(tbl) { columnsOf(tbl) }
            if (applyOne(table, columns, change, fromDevice)) applied++
          }
        } finally {
          exec("UPDATE sync_kv SET v = '0' WHERE k = 'applying'")
        }
      }
    }
    if (applied > 0) db.invalidationTracker.refreshAsync()
    applied
  }

  private suspend fun PooledConnection.applyOne(table: Table, columns: Set<String>, change: JSONObject, fromDevice: String): Boolean {
    val uid = change.getString("u")
    val at = change.getLong("at")
    val deleted = change.optBoolean("d", false)
    val local = query("SELECT local_id, updated_at FROM sync_ids WHERE uid = ?", { bindText(1, uid) }) {
      it.getLong(0) to it.getLong(1)
    }.firstOrNull()
    if (local != null && local.second >= at) return false // ours is as new or newer
    val seqSql = "(SELECT IFNULL(MAX(seq), 0) + 1 FROM sync_ids)"

    if (deleted) {
      if (local == null) return false
      exec("DELETE FROM ${table.name} WHERE id = ?") { bindLong(1, local.first) }
      exec("UPDATE sync_ids SET deleted = 1, updated_at = ?, seq = $seqSql, src = ? WHERE uid = ?") {
        bindLong(1, at); bindText(2, fromDevice); bindText(3, uid)
      }
      return true
    }

    val row = change.getJSONObject("row")
    val refs = change.optJSONObject("refs") ?: JSONObject()
    for ((column, parent) in table.refs) {
      if (!row.has(column)) continue
      val parentUid = refs.optString(column, "")
      if (parentUid.isNotEmpty()) {
        val parentLocal = query("SELECT local_id FROM sync_ids WHERE uid = ? AND tbl = ?", { bindText(1, parentUid); bindText(2, parent) }) {
          it.getLong(0)
        }.firstOrNull()
        row.put(column, parentLocal ?: if (row.isNull(column)) JSONObject.NULL else 0L)
      } else if (!row.isNull(column)) {
        row.put(column, 0L) // pointed at something the peer never had
      }
    }
    val names = row.keys().asSequence().filter { it in columns && it != "id" }.toList()
    if (names.isEmpty()) return false

    // A record deleted here but edited later on the peer comes back.
    val existing = local?.first?.takeIf { id -> query("SELECT 1 FROM ${table.name} WHERE id = ?", { bindLong(1, id) }) { true }.isNotEmpty() }
    val localId = if (existing != null) {
      exec("UPDATE ${table.name} SET ${names.joinToString { "$it = ?" }} WHERE id = ?") {
        names.forEachIndexed { i, n -> bindJson(i + 1, row.opt(n)) }
        bindLong(names.size + 1, existing)
      }
      existing
    } else {
      exec("INSERT INTO ${table.name} (${names.joinToString()}) VALUES (${names.joinToString { "?" }})") {
        names.forEachIndexed { i, n -> bindJson(i + 1, row.opt(n)) }
      }
      query("SELECT last_insert_rowid()") { it.getLong(0) }.first()
    }
    if (local != null) {
      exec("UPDATE sync_ids SET local_id = ?, updated_at = ?, deleted = 0, seq = $seqSql, src = ? WHERE uid = ?") {
        bindLong(1, localId); bindLong(2, at); bindText(3, fromDevice); bindText(4, uid)
      }
    } else {
      exec("INSERT INTO sync_ids (tbl, local_id, uid, updated_at, deleted, seq, src) VALUES (?, ?, ?, ?, 0, $seqSql, ?)") {
        bindText(1, table.name); bindLong(2, localId); bindText(3, uid); bindLong(4, at); bindText(5, fromDevice)
      }
    }
    return true
  }

  // --- Accounts ---

  /** Saved profiles and their password hashes, so the same sign-in works on both devices. */
  fun accountsPayload(): JSONObject {
    val accounts = runCatching { JSONArray(authPrefs.getString(ACCOUNTS_KEY, null) ?: "[]") }.getOrDefault(JSONArray())
    val passwords = JSONObject()
    for (i in 0 until accounts.length()) {
      val id = accounts.optJSONObject(i)?.optString("id").orEmpty()
      authPrefs.getString("password_hash_$id", null)?.let { passwords.put(id, it) }
    }
    val active = if (authPrefs.getBoolean("is_logged_in", false)) authPrefs.getString("active_user_id", null) else null
    return JSONObject().put("accounts", accounts).put("passwords", passwords).put("active", active ?: JSONObject.NULL)
  }

  /** Adds the peer's profiles that aren't here and keeps the most recently used copy of each. */
  fun mergeAccounts(payload: JSONObject): Boolean {
    val incoming = payload.optJSONArray("accounts") ?: return false
    val passwords = payload.optJSONObject("passwords") ?: JSONObject()
    val mine = runCatching { JSONArray(authPrefs.getString(ACCOUNTS_KEY, null) ?: "[]") }.getOrDefault(JSONArray())
    val byId = LinkedHashMap<String, JSONObject>()
    for (i in 0 until mine.length()) mine.optJSONObject(i)?.let { byId[it.optString("id")] = it }
    var changed = false
    for (i in 0 until incoming.length()) {
      val account = incoming.optJSONObject(i) ?: continue
      val id = account.optString("id")
      if (id.isBlank()) continue
      val current = byId[id]
      if (current == null || account.optLong("lastLoginTime") > current.optLong("lastLoginTime")) {
        // isLoggedIn describes the other device's session; keep this device's.
        account.put("isLoggedIn", current?.optBoolean("isLoggedIn", false) ?: false)
        byId[id] = account
        changed = true
      }
    }
    authPrefs.edit {
      if (changed) putString(ACCOUNTS_KEY, JSONArray(byId.values).toString())
      for (id in passwords.keys()) {
        if (!authPrefs.contains("password_hash_$id")) {
          putString("password_hash_$id", passwords.getString(id))
          changed = true
        }
      }
    }
    return changed
  }

  // --- SQL helpers ---

  private class Change(val tbl: String, val localId: Long, val uid: String, val updatedAt: Long, val deleted: Boolean)

  private suspend fun <R> Transactor.immediate(block: suspend PooledConnection.() -> R): R =
    withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }

  private suspend fun PooledConnection.columnsOf(table: String): Set<String> =
    query("PRAGMA table_info($table)") { it.getText(1) }.toSet()

  private suspend fun PooledConnection.uidOf(table: String, localId: Long): String? =
    query("SELECT uid FROM sync_ids WHERE tbl = ? AND local_id = ?", { bindText(1, table); bindLong(2, localId) }) { it.getText(0) }
      .firstOrNull()

  private suspend fun PooledConnection.readRow(table: String, id: Long): JSONObject? =
    query("SELECT * FROM $table WHERE id = ?", { bindLong(1, id) }) { stmt ->
      JSONObject().also { row ->
        for (i in 0 until stmt.getColumnCount()) {
          val name = stmt.getColumnName(i)
          when (stmt.getColumnType(i)) {
            SQLITE_DATA_NULL -> row.put(name, JSONObject.NULL)
            SQLITE_DATA_INTEGER -> row.put(name, stmt.getLong(i))
            SQLITE_DATA_FLOAT -> row.put(name, stmt.getDouble(i))
            SQLITE_DATA_BLOB -> {}
            else -> row.put(name, stmt.getText(i))
          }
        }
      }
    }.firstOrNull()

  companion object {
    private const val ACCOUNTS_KEY = "registered_students_json"
    private const val NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
    private const val NOT_APPLYING = "NOT EXISTS (SELECT 1 FROM sync_kv WHERE k = 'applying' AND v = '1')"
    private const val TOUCH = "updated_at = $NOW_MS, seq = (SELECT IFNULL(MAX(seq), 0) + 1 FROM sync_ids), " +
      "src = IFNULL((SELECT v FROM sync_kv WHERE k = 'device'), '')"
  }
}

private fun SQLiteStatement.bindJson(index: Int, value: Any?) {
  when (value) {
    null, JSONObject.NULL -> bindNull(index)
    is Boolean -> bindLong(index, if (value) 1 else 0)
    is Int -> bindLong(index, value.toLong())
    is Long -> bindLong(index, value)
    is Number -> if (value.toDouble() % 1.0 == 0.0 && value.toDouble() in -9e15..9e15) bindLong(index, value.toLong()) else bindDouble(index, value.toDouble())
    else -> bindText(index, value.toString())
  }
}

/** Runs [sql] and maps every result row. */
internal suspend fun <R> PooledConnection.query(sql: String, bind: SQLiteStatement.() -> Unit = {}, map: (SQLiteStatement) -> R): List<R> =
  usePrepared(sql) { stmt ->
    stmt.bind()
    buildList { while (stmt.step()) add(map(stmt)) }
  }

internal suspend fun PooledConnection.exec(sql: String, bind: SQLiteStatement.() -> Unit = {}) {
  usePrepared(sql) { stmt ->
    stmt.bind()
    stmt.step()
  }
}
