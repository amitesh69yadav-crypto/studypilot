package com.example.data.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A course from a learning system, in the shape the app stores: subject, topics and tasks. */
data class LmsCourse(
  val name: String,
  val code: String,
  /** Syllabus, module and page texts, each becoming a topic with these notes. */
  val topics: List<LmsTopic>,
  val assignments: List<LmsAssignment>
)

data class LmsTopic(val title: String, val text: String)

/** [dueDate] is "yyyy-MM-dd" in this device's time zone, or blank when there is none. */
data class LmsAssignment(val title: String, val dueDate: String, val isExam: Boolean)

/** A learning system the app can read courses from. */
interface LmsSource {
  val label: String
  suspend fun courses(): List<LmsCourse>
}

class LmsException(message: String) : Exception(message)

object LmsHttp {
  val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  suspend fun get(url: String, bearer: String? = null): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).apply { if (bearer != null) header("Authorization", "Bearer $bearer") }.build()
    client.newCall(request).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      when {
        resp.code == 401 || resp.code == 403 -> throw LmsException("The service refused the sign-in or token (${resp.code}). Check it and try again.")
        resp.code == 404 -> throw LmsException("Nothing found at that address (404). Check the URL.")
        !resp.isSuccessful -> throw LmsException("The service answered ${resp.code}.")
      }
      body
    }
  }

  /** Base URL as typed ("canvas.uni.edu", "https://canvas.uni.edu/") to "https://canvas.uni.edu". */
  fun base(url: String): String {
    val t = url.trim().trimEnd('/')
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
  }

  /**
   * Canvas always lives at the root of its site, so any page path copied from the browser
   * ("https://canvas.uni.edu/profile/settings") is dropped.
   */
  fun siteRoot(url: String): String {
    val full = base(url)
    val parsed = full.toHttpUrlOrNull() ?: return full
    val port = if (parsed.port == okhttp3.HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
    return "${parsed.scheme}://${parsed.host}$port"
  }

  /**
   * Moodle can live in a folder ("uni.edu/moodle"), so only the page part of a copied address is
   * dropped: everything from "/login", "/my", "/course", "/user", "/admin", "/mod" or a ".php" page on.
   */
  fun moodleRoot(url: String): String {
    val full = base(url)
    val cut = Regex("/(login|my|course|user|admin|mod|grade|calendar|message)(/|$)|/[^/]+\\.php").find(full.substringAfter("://"))
    return if (cut == null) full else full.substringBefore("://") + "://" + full.substringAfter("://").substring(0, cut.range.first)
  }
}

/** Plain text from the HTML learning systems store pages and syllabi in. */
object Html {
  fun toText(html: String?): String {
    if (html.isNullOrBlank()) return ""
    return html
      .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
      .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</tr>"), "\n")
      .replace(Regex("(?i)<li[^>]*>"), "\n- ")
      .replace(Regex("<[^>]+>"), "")
      .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
      .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
      .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString().orEmpty() }
      .lines().map { it.trim() }
      .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
  }
}

private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun localDay(millis: Long) = DAY.apply { timeZone = TimeZone.getDefault() }.format(millis)
private fun looksLikeExam(title: String) = Regex("(?i)\\b(exam|midterm|final|test|quiz|tenta)").containsMatchIn(title)

/** ISO 8601 ("2026-12-01T22:59:59Z", with or without millis or offset) to a local date, or blank. */
internal fun isoToLocalDay(iso: String?): String {
  if (iso.isNullOrBlank() || iso == "null") return ""
  val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  for (p in patterns) {
    val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    runCatching { return localDay(f.parse(iso)!!.time) }
  }
  return iso.take(10).takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }.orEmpty()
}

/**
 * Canvas, with a personal access token (Account > Settings > New access token). Reads active
 * courses, their syllabus, wiki pages and assignments.
 */
class CanvasSource(baseUrl: String, private val token: String) : LmsSource {
  override val label = "Canvas"
  private val base = LmsHttp.siteRoot(baseUrl)

  private suspend fun list(path: String): JSONArray = JSONArray(LmsHttp.get("$base/api/v1/$path", token))

  override suspend fun courses(): List<LmsCourse> {
    val courses = list("courses?enrollment_state=active&include[]=syllabus_body&per_page=100")
    return (0 until courses.length()).mapNotNull { courses.optJSONObject(it) }
      .filter { !it.optBoolean("access_restricted_by_date", false) && it.has("name") }
      .map { c ->
        val id = c.getLong("id")
        val topics = mutableListOf<LmsTopic>()
        Html.toText(c.optString("syllabus_body", "")).takeIf { it.length > 20 }?.let { topics += LmsTopic("Syllabus", it) }
        runCatching {
          val pages = list("courses/$id/pages?per_page=30&sort=updated_at&order=desc")
          for (i in 0 until minOf(pages.length(), 15)) {
            val slug = pages.optJSONObject(i)?.optString("url").orEmpty()
            if (slug.isBlank()) continue
            val page = JSONObject(LmsHttp.get("$base/api/v1/courses/$id/pages/$slug", token))
            Html.toText(page.optString("body")).takeIf { it.length > 40 }?.let { topics += LmsTopic(page.optString("title", slug), it) }
          }
        }
        val assignments = runCatching {
          val a = list("courses/$id/assignments?per_page=100&order_by=due_at")
          (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { x ->
            val title = x.optString("name", "Assignment")
            LmsAssignment(title, isoToLocalDay(x.optString("due_at")), looksLikeExam(title))
          }
        }.getOrDefault(emptyList())
        LmsCourse(c.optString("name").trim(), c.optString("course_code").trim(), topics, assignments)
      }
  }
}

/**
 * Moodle, with a web-service token (Profile > Preferences > Security keys, "Moodle mobile web
 * service"). Reads enrolled courses, each section's summary and activities, and assignments.
 */
class MoodleSource(baseUrl: String, private val token: String) : LmsSource {
  override val label = "Moodle"
  private val base = LmsHttp.moodleRoot(baseUrl)

  private suspend fun call(function: String, vararg args: Pair<String, String>): Any {
    val url = "$base/webservice/rest/server.php".toHttpUrlOrNull()?.newBuilder()
      ?.addQueryParameter("wstoken", token)
      ?.addQueryParameter("moodlewsrestformat", "json")
      ?.addQueryParameter("wsfunction", function)
      ?.apply { args.forEach { (k, v) -> addQueryParameter(k, v) } }
      ?.build() ?: throw LmsException("That isn't a valid Moodle address.")
    val text = LmsHttp.get(url.toString())
    val json: Any = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    if (json is JSONObject && json.has("exception")) throw LmsException("Moodle: ${json.optString("message", "the request failed")}")
    return json
  }

  override suspend fun courses(): List<LmsCourse> {
    val me = call("core_webservice_get_site_info") as JSONObject
    val list = call("core_enrol_get_users_courses", "userid" to me.getLong("userid").toString()) as JSONArray
    val courses = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
    val assignmentsByCourse = runCatching {
      val args = courses.mapIndexed { i, c -> "courseids[$i]" to c.getLong("id").toString() }.toTypedArray()
      val res = call("mod_assign_get_assignments", *args) as JSONObject
      val arr = res.optJSONArray("courses") ?: JSONArray()
      (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.associate { c ->
        val a = c.optJSONArray("assignments") ?: JSONArray()
        c.getLong("id") to (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { x ->
          val title = x.optString("name", "Assignment")
          val due = x.optLong("duedate", 0L)
          LmsAssignment(title, if (due > 0) localDay(due * 1000) else "", looksLikeExam(title))
        }
      }
    }.getOrDefault(emptyMap())
    return courses.map { c ->
      val id = c.getLong("id")
      val topics = mutableListOf<LmsTopic>()
      Html.toText(c.optString("summary")).takeIf { it.length > 20 }?.let { topics += LmsTopic("Course overview", it) }
      runCatching {
        val sections = call("core_course_get_contents", "courseid" to id.toString()) as JSONArray
        for (i in 0 until sections.length()) {
          val s = sections.optJSONObject(i) ?: continue
          val modules = s.optJSONArray("modules") ?: JSONArray()
          val text = buildString {
            append(Html.toText(s.optString("summary")))
            for (m in 0 until modules.length()) {
              val mod = modules.optJSONObject(m) ?: continue
              append("\n- ").append(mod.optString("name"))
              Html.toText(mod.optString("description")).takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(600)) }
            }
          }.trim()
          if (text.length > 20) topics += LmsTopic(s.optString("name").ifBlank { "Section ${i + 1}" }, text)
        }
      }
      LmsCourse(Html.toText(c.optString("fullname")).ifBlank { c.optString("shortname") }, c.optString("shortname").trim(), topics, assignmentsByCourse[id].orEmpty())
    }
  }
}

/**
 * Google Classroom, with an access token from Google sign-in (read-only course, coursework and
 * material scopes). Reads active courses, their coursework, materials and recent announcements.
 */
class ClassroomSource(private val accessToken: String) : LmsSource {
  override val label = "Google Classroom"
  private val api = "https://classroom.googleapis.com/v1"

  private suspend fun get(path: String): JSONObject = JSONObject(LmsHttp.get("$api/$path", accessToken))

  override suspend fun courses(): List<LmsCourse> {
    val list = get("courses?courseStates=ACTIVE&pageSize=50").optJSONArray("courses") ?: JSONArray()
    return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { c ->
      val id = c.getString("id")
      val topics = mutableListOf<LmsTopic>()
      c.optString("description").takeIf { it.length > 20 }?.let { topics += LmsTopic("Course overview", it) }
      runCatching {
        val materials = get("courses/$id/courseWorkMaterials?pageSize=30").optJSONArray("courseWorkMaterial") ?: JSONArray()
        for (i in 0 until materials.length()) {
          val m = materials.optJSONObject(i) ?: continue
          val text = (m.optString("description") + "\n" + materialLinks(m.optJSONArray("materials"))).trim()
          if (text.length > 10) topics += LmsTopic(m.optString("title", "Material"), text)
        }
      }
      runCatching {
        val posts = get("courses/$id/announcements?pageSize=10").optJSONArray("announcements") ?: JSONArray()
        val text = (0 until posts.length()).mapNotNull { posts.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }.joinToString("\n\n")
        if (text.length > 20) topics += LmsTopic("Announcements", text)
      }
      val work = runCatching {
        val arr = get("courses/$id/courseWork?pageSize=50").optJSONArray("courseWork") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { w ->
          val title = w.optString("title", "Assignment")
          LmsAssignment(title, classroomDue(w), looksLikeExam(title))
        }
      }.getOrDefault(emptyList())
      LmsCourse(c.optString("name").trim(), c.optString("section").trim(), topics, work)
    }
  }

  private fun materialLinks(materials: JSONArray?): String {
    materials ?: return ""
    return (0 until materials.length()).mapNotNull { materials.optJSONObject(it) }.mapNotNull { m ->
      m.optJSONObject("driveFile")?.optJSONObject("driveFile")?.let { "- ${it.optString("title")}: ${it.optString("alternateLink")}" }
        ?: m.optJSONObject("link")?.let { "- ${it.optString("title", it.optString("url"))}: ${it.optString("url")}" }
        ?: m.optJSONObject("youtubeVideo")?.let { "- ${it.optString("title")}: ${it.optString("alternateLink")}" }
    }.joinToString("\n")
  }

  /** Classroom gives the due date and time in UTC as separate parts. */
  private fun classroomDue(w: JSONObject): String {
    val d = w.optJSONObject("dueDate") ?: return ""
    val t = w.optJSONObject("dueTime")
    val iso = "%04d-%02d-%02dT%02d:%02d:00Z".format(d.optInt("year"), d.optInt("month"), d.optInt("day"), t?.optInt("hours") ?: 23, t?.optInt("minutes") ?: 59)
    return isoToLocalDay(iso)
  }
}
