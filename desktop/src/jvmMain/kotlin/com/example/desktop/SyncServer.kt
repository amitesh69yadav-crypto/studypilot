package com.example.desktop

import com.example.data.sync.SYNC_PORT
import com.example.data.sync.SyncHost
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.application.ApplicationCall
import org.json.JSONObject

/** Serves /pair and /sync to phones on the same network. Returns null if the port is taken. */
fun startSyncServer(host: SyncHost): EmbeddedServer<*, *>? = try {
  embeddedServer(CIO, port = SYNC_PORT, host = "0.0.0.0") {
    routing {
      get("/") { call.respondText("StudyPilot on ${host.hostName}") }
      post("/pair") {
        val (status, body) = host.handlePair(call.jsonBody())
        call.respondJson(status, body)
      }
      post("/alarm") {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val (status, body) = host.handleAlarm(token, call.jsonBody())
        call.respondJson(status, body)
      }
      post("/sync") {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val (status, body) = host.handleSync(token, call.jsonBody())
        call.respondJson(status, body)
      }
    }
  }.start(wait = false)
} catch (e: Exception) {
  System.err.println("Sync server could not start on port $SYNC_PORT: ${e.message}")
  null
}

private suspend fun ApplicationCall.jsonBody(): JSONObject = runCatching { JSONObject(receiveText()) }.getOrDefault(JSONObject())

private suspend fun ApplicationCall.respondJson(status: Int, body: JSONObject) =
  respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(status))
