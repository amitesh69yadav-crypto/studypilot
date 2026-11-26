package com.example.data.sync

import com.example.platform.KeyValueStore
import com.example.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** One finished exchange with the other device. */
data class SyncResult(
  val peerName: String,
  val applied: Int,
  val accountsChanged: Boolean,
  /** The account open on the other device, or null for guest mode. */
  val peerActiveUserId: String?,
  val firstSync: Boolean
)

/** Lets the viewmodel react to data that arrived from the other device. */
object SyncEvents {
  private val _results = MutableSharedFlow<SyncResult>(extraBufferCapacity = 8)
  val results: SharedFlow<SyncResult> = _results

  fun emit(result: SyncResult) {
    _results.tryEmit(result)
  }
}

const val SYNC_PORT = 47321
private const val PAIR_SCHEME = "studypilot://pair"
private val JSON = "application/json; charset=utf-8".toMediaType()

private fun randomHex(bytes: Int): String =
  ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

/**
 * The PC side. Shows a pairing code, hands each paired phone a token, and answers sync requests.
 * The HTTP server itself lives in the desktop app; this class only turns requests into responses.
 */
class SyncHost(private val engine: SyncEngine, private val prefs: KeyValueStore, val hostName: String) {

  data class Peer(val token: String, val name: String, val deviceId: String, val lastSyncAt: Long)

  private val lock = Mutex()
  private val _pairingCode = MutableStateFlow<String?>(null)
  /** Six digits the phone must send to pair; null when pairing isn't open. */
  val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()
  private var failedAttempts = 0

  private val _peers = MutableStateFlow(loadPeers())
  val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

  fun startPairing(): String {
    val code = (SecureRandom().nextInt(900_000) + 100_000).toString()
    failedAttempts = 0
    _pairingCode.value = code
    return code
  }

  fun stopPairing() {
    _pairingCode.value = null
  }

  /** What the phone scans: where to reach this PC (best address first) and the code. */
  fun pairingUri(addresses: List<String>, code: String): String =
    "$PAIR_SCHEME?h=${addresses.joinToString(",")}&p=$SYNC_PORT&c=$code&n=${java.net.URLEncoder.encode(hostName, "UTF-8")}"

  fun forget(token: String) {
    savePeers(_peers.value.filterNot { it.token == token })
  }

  /** POST /pair {code, name} -> {token, hostId, hostName}. */
  suspend fun handlePair(body: JSONObject): Pair<Int, JSONObject> = lock.withLock {
    val expected = _pairingCode.value ?: return@withLock 403 to error("Pairing isn't open on the PC. Open Settings > Phone on the PC first.")
    if (body.optString("code") != expected) {
      // A handful of guesses, then the code changes.
      if (++failedAttempts >= 5) startPairing()
      return@withLock 403 to error("That code doesn't match. Check the code on the PC.")
    }
    engine.prepare()
    val token = randomHex(24)
    val name = body.optString("name").ifBlank { "Phone" }.take(60)
    savePeers(_peers.value + Peer(token, name, "", 0L))
    stopPairing()
    200 to JSONObject().put("token", token).put("hostId", engine.deviceId).put("hostName", hostName)
  }

  /** POST /sync with a bearer token; see [SyncClient.syncNow] for the body. */
  suspend fun handleSync(token: String?, body: JSONObject): Pair<Int, JSONObject> {
    val peer = _peers.value.firstOrNull { it.token == token } ?: return 401 to error("This phone isn't paired with the PC any more. Pair again.")
    val deviceId = body.optString("deviceId")
    if (deviceId.isBlank()) return 400 to error("Missing deviceId")
    engine.prepare()

    val applied = engine.apply(body.optJSONArray("changes") ?: JSONArray(), fromDevice = deviceId)
    val incomingAccounts = body.optJSONObject("accounts")
    val accountsChanged = incomingAccounts?.let { engine.mergeAccounts(it) } ?: false

    // A since from another copy of this database (it was reset) means nothing; send everything.
    val since = if (body.optString("epoch") == engine.deviceId) body.optLong("since", 0L) else 0L
    val seq = engine.currentSeq()
    val changes = engine.changesSince(since, seq, excludeDevice = deviceId)

    lock.withLock {
      val name = body.optString("name").take(60).ifBlank { peer.name }
      savePeers(_peers.value.map { if (it.token == token) it.copy(name = name, deviceId = deviceId, lastSyncAt = System.currentTimeMillis()) else it })
    }
    SyncEvents.emit(
      SyncResult(
        peerName = peer.name,
        applied = applied,
        accountsChanged = accountsChanged,
        peerActiveUserId = incomingAccounts?.optString("active")?.takeIf { it.isNotBlank() && it != "null" },
        firstSync = peer.lastSyncAt == 0L
      )
    )
    return 200 to JSONObject()
      .put("epoch", engine.deviceId)
      .put("seq", seq)
      .put("changes", changes)
      .put("accounts", engine.accountsPayload())
      .put("hostName", hostName)
  }

  private fun error(message: String) = JSONObject().put("error", message)

  private fun loadPeers(): List<Peer> = runCatching {
    val arr = JSONArray(prefs.getString("peers", "[]") ?: "[]")
    (0 until arr.length()).map { i ->
      val o = arr.getJSONObject(i)
      Peer(o.getString("token"), o.optString("name", "Phone"), o.optString("deviceId"), o.optLong("lastSyncAt"))
    }
  }.getOrDefault(emptyList())

  private fun savePeers(list: List<Peer>) {
    _peers.value = list
    val arr = JSONArray()
    list.forEach {
      arr.put(JSONObject().put("token", it.token).put("name", it.name).put("deviceId", it.deviceId).put("lastSyncAt", it.lastSyncAt))
    }
    prefs.edit { putString("peers", arr.toString()) }
  }
}

/** The phone side: pairs with a PC once, then swaps changes with it. */
class SyncClient(private val engine: SyncEngine, private val prefs: KeyValueStore, private val deviceName: String) {

  data class State(
    val pcName: String? = null,
    val address: String? = null,
    val lastSyncAt: Long = 0L,
    val syncing: Boolean = false,
    val error: String? = null
  ) {
    val paired: Boolean get() = address != null
  }

  private val http = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()
  private val lock = Mutex()

  private val _state = MutableStateFlow(
    State(
      pcName = prefs.getString("host_name", null),
      address = prefs.getString("address", null),
      lastSyncAt = prefs.getLong("last_sync_at", 0L)
    )
  )
  val state: StateFlow<State> = _state.asStateFlow()

  /** Pairs from the scanned QR text ("studypilot://pair?h=...&p=...&c=..."). */
  suspend fun pairFromQr(text: String): Boolean {
    val query = runCatching { URI(text.trim()).rawQuery }.getOrNull()
    if (!text.trim().startsWith(PAIR_SCHEME) || query == null) {
      _state.value = _state.value.copy(error = "That isn't a StudyPilot pairing code. Scan the code shown on the PC.")
      return false
    }
    val params = query.split("&").mapNotNull { part ->
      val i = part.indexOf('=')
      if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
    }.toMap()
    // The PC lists every address it has (Wi-Fi, Ethernet, ...); use the first one that answers.
    val hosts = params["h"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val port = params["p"]?.toIntOrNull() ?: SYNC_PORT
    for (host in hosts) {
      if (pair("$host:$port", params["c"].orEmpty())) return true
      if (_state.value.error?.startsWith("Can't reach") != true) return false
    }
    return false
  }

  /** Pairs with the PC at [address] ("192.168.1.60" or "192.168.1.60:47321") using the code it shows. */
  suspend fun pair(address: String, code: String): Boolean = lock.withLock {
    val target = address.trim().removePrefix("http://").trimEnd('/').let { if (':' in it) it else "$it:$SYNC_PORT" }
    _state.value = _state.value.copy(syncing = true, error = null)
    val result = runCatching { post(target, "/pair", null, JSONObject().put("code", code.trim()).put("name", deviceName)) }
    val (status, body) = result.getOrElse {
      _state.value = _state.value.copy(syncing = false, error = "Can't reach the PC at $target. Is StudyPilot open there, and are both on the same Wi-Fi?")
      return@withLock false
    }
    if (status != 200) {
      _state.value = _state.value.copy(syncing = false, error = body.optString("error", "Pairing failed ($status)"))
      return@withLock false
    }
    prefs.edit {
      putString("address", target)
      putString("token", body.getString("token"))
      putString("host_id", body.optString("hostId"))
      putString("host_name", body.optString("hostName", "PC"))
      putLong("pulled", 0L)
      putLong("pushed", 0L)
      putString("pushed_epoch", null)
      putLong("last_sync_at", 0L)
    }
    _state.value = State(pcName = body.optString("hostName", "PC"), address = target)
    true
  }.also { if (it) syncNow() }

  fun unpair() {
    prefs.edit {
      listOf("address", "token", "host_id", "host_name", "pulled", "pushed", "pushed_epoch", "last_sync_at").forEach { remove(it) }
    }
    _state.value = State()
  }

  /** Sends this phone's changes and fetches the PC's. Returns false when it couldn't reach the PC. */
  suspend fun syncNow(): Boolean = lock.withLock {
    val address = prefs.getString("address", null) ?: return@withLock false
    val token = prefs.getString("token", null) ?: return@withLock false
    _state.value = _state.value.copy(syncing = true)
    try {
      engine.prepare()
      val mySeq = engine.currentSeq()
      val pushedFrom = if (prefs.getString("pushed_epoch", null) == engine.deviceId) prefs.getLong("pushed", 0L) else 0L
      val hostId = prefs.getString("host_id", null)
      val firstSync = prefs.getLong("last_sync_at", 0L) == 0L
      val body = JSONObject()
        .put("deviceId", engine.deviceId)
        .put("name", deviceName)
        .put("epoch", hostId ?: "")
        .put("since", prefs.getLong("pulled", 0L))
        .put("changes", engine.changesSince(pushedFrom, mySeq, excludeDevice = hostId))
        .put("accounts", engine.accountsPayload())
      val (status, reply) = post(address, "/sync", token, body)
      if (status == 401) {
        unpair()
        _state.value = State(error = reply.optString("error", "The PC no longer knows this phone. Pair again."))
        return@withLock false
      }
      if (status != 200) error(reply.optString("error", "PC answered $status"))
      val epoch = reply.getString("epoch")
      val applied = engine.apply(reply.optJSONArray("changes") ?: JSONArray(), fromDevice = epoch)
      val peerAccounts = reply.optJSONObject("accounts")
      val accountsChanged = peerAccounts?.let { engine.mergeAccounts(it) } ?: false
      val now = System.currentTimeMillis()
      prefs.edit {
        putString("host_id", epoch)
        putLong("pulled", reply.optLong("seq", 0L))
        putLong("pushed", mySeq)
        putString("pushed_epoch", engine.deviceId)
        putString("host_name", reply.optString("hostName", "PC"))
        putLong("last_sync_at", now)
      }
      _state.value = State(pcName = reply.optString("hostName", "PC"), address = address, lastSyncAt = now)
      SyncEvents.emit(
        SyncResult(
          peerName = reply.optString("hostName", "PC"),
          applied = applied,
          accountsChanged = accountsChanged,
          peerActiveUserId = peerAccounts?.optString("active")?.takeIf { it.isNotBlank() && it != "null" },
          firstSync = firstSync
        )
      )
      true
    } catch (e: Exception) {
      Log.w("SyncClient", "Sync with $address failed", e)
      _state.value = _state.value.copy(syncing = false, error = "Can't reach ${_state.value.pcName ?: "the PC"} right now.")
      false
    }
  }

  private suspend fun post(address: String, path: String, token: String?, body: JSONObject): Pair<Int, JSONObject> =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url("http://$address$path")
        .post(body.toString().toRequestBody(JSON))
        .apply { if (token != null) header("Authorization", "Bearer $token") }
        .build()
      http.newCall(request).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        resp.code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
      }
    }
}
