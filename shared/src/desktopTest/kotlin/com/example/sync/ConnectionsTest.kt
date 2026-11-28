package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.connect.CanvasSource
import com.example.data.connect.Html
import com.example.data.connect.Ical
import com.example.data.connect.MoodleSource
import com.example.data.local.AppDatabase
import com.example.data.repository.StudyPilotRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Calendar feeds, Canvas and Moodle imports. */
class ConnectionsTest {
  private val stockholm = TimeZone.getTimeZone("Europe/Stockholm")
  private fun millis(day: String) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = stockholm }.parse(day)!!.time

  private val feed = """
    BEGIN:VCALENDAR
    VERSION:2.0
    BEGIN:VEVENT
    UID:lec-1@timeedit
    DTSTART;TZID=Europe/Stockholm:20261130T081500
    DTEND;TZID=Europe/Stockholm:20261130T100000
    RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261211T235959Z
    EXDATE;TZID=Europe/Stockholm:20261202T081500
    SUMMARY:DA101 Data Structures\, Lecture
    LOCATION:Room B12
    DESCRIPTION:Teacher: A. Smith\nBring
     your laptop
    END:VEVENT
    BEGIN:VEVENT
    UID:lab-7
    DTSTART:20261201T130000Z
    DTEND:20261201T150000Z
    SUMMARY:DA101 Lab
    END:VEVENT
    BEGIN:VEVENT
    UID:exam-1
    DTSTART;VALUE=DATE:20261215
    SUMMARY:DA101 Final exam
    END:VEVENT
    BEGIN:VEVENT
    UID:gone
    STATUS:CANCELLED
    DTSTART:20261203T090000Z
    SUMMARY:Cancelled seminar
    END:VEVENT
    END:VCALENDAR
  """.trimIndent()

  @Test
  fun calendarFeedIsReadWithZonesRepeatsAndExceptions() {
    val events = Ical.parse(feed, millis("2026-11-28"), millis("2027-01-31"), stockholm)
    val lectures = events.filter { it.uid == "lec-1@timeedit" }
    // Mon 30 Nov, (Wed 2 Dec excluded), Mon 7, Wed 9 Dec; nothing after UNTIL.
    assertEquals(listOf("2026-11-30", "2026-12-07", "2026-12-09"), lectures.map { it.date })
    assertEquals("08:15" to "10:00", lectures.first().startTime to lectures.first().endTime)
    assertEquals("DA101 Data Structures, Lecture", lectures.first().summary)
    assertEquals("Teacher: A. Smith\nBringyour laptop", lectures.first().description)
    // 13:00 UTC is 14:00 in Stockholm in December.
    assertEquals("14:00", events.first { it.uid == "lab-7" }.startTime)
    assertTrue(events.first { it.uid == "exam-1" }.allDay)
    assertTrue(events.none { it.uid == "gone" })
  }

  @Test
  fun calendarImportIsRepeatableAndFollowsChanges() = runBlocking<Unit> {
    withRepo { repo, db ->
      val window = millis("2026-11-28") to millis("2027-01-31")
      val events = Ical.parse(feed, window.first, window.second, stockholm)
      val first = repo.importCalendar("u", "https://uni.example/feed.ics", events, "2026-11-28")
      assertEquals(4, first.sessions) // three lectures and the lab
      assertEquals(1, first.tasks) // the all-day exam
      val again = repo.importCalendar("u", "https://uni.example/feed.ics", events, "2026-11-28")
      assertEquals("Already up to date", again.toString())

      // The lab moves an hour and one lecture disappears from the feed.
      val moved = feed.replace("20261201T130000Z", "20261201T140000Z").replace("20261201T150000Z", "20261201T160000Z")
        .replace("UNTIL=20261211T235959Z", "UNTIL=20261208T235959Z")
      val third = repo.importCalendar("u", "https://uni.example/feed.ics", Ical.parse(moved, window.first, window.second, stockholm), "2026-11-28")
      assertEquals(1, third.updated)
      assertEquals(1, third.removed)
      val sessions = db.studyPilotDao().getSessionsListForUser("u")
      assertEquals(3, sessions.size)
      assertTrue(sessions.all { !it.isAlarmEnabled })
      assertEquals("15:00", sessions.first { it.topic == "DA101 Lab" }.startTime)
    }
  }

  @Test
  fun canvasAndMoodleCoursesBecomeSubjectsTopicsAndTasks() = runBlocking<Unit> {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    fun route(path: String, body: () -> String) = server.createContext(path) { ex ->
      val bytes = body().toByteArray()
      ex.sendResponseHeaders(if (ex.requestHeaders.getFirst("Authorization") == "Bearer revoked") 401 else 200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    route("/api/v1/courses") {
      """[{"id":7,"name":"Data Structures","course_code":"DA101","syllabus_body":"<p>Week 1: <b>arrays</b> and lists</p><ul><li>Week 2: trees</li></ul>"}]"""
    }
    route("/api/v1/courses/7/pages") { """[{"url":"heaps","title":"Heaps"}]""" }
    route("/api/v1/courses/7/pages/heaps") { """{"title":"Heaps","body":"<p>A binary heap keeps the smallest item at the root &amp; supports O(log n) insert.</p>"}""" }
    route("/api/v1/courses/7/assignments") {
      """[{"name":"Lab 3: Heaps","due_at":"2026-12-04T22:59:00Z"},{"name":"Midterm exam","due_at":"2026-12-10T08:00:00Z"},{"name":"Old lab","due_at":"2026-10-01T10:00:00Z"},{"name":"No date","due_at":null}]"""
    }
    route("/webservice/rest/server.php") {
      """{"userid":5}"""
    }
    server.start()
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val courses = CanvasSource(base, "token").courses()
      assertEquals(listOf("Syllabus", "Heaps"), courses.single().topics.map { it.title })
      assertEquals("Week 1: arrays and lists\n\n- Week 2: trees", courses.single().topics.first().text)
      withRepo { repo, db ->
        val result = repo.importCourses("u", courses, "2026-11-28")
        assertEquals(1, result.subjects)
        assertEquals(2, result.topics)
        assertEquals(2, result.tasks) // the past lab and the undated one are skipped
        val tasks = db.studyPilotDao().getTasksForUser("u").first()
        assertEquals("EXAM", tasks.first { it.title == "Midterm exam" }.type)
        assertEquals("Already up to date", repo.importCourses("u", courses, "2026-11-28").toString())
        // Removing the course takes its imported topics with it, and a skipped course isn't re-added.
        val subject = db.studyPilotDao().getSubjectsForUser("u").first().single()
        repo.deleteSubject(subject)
        assertTrue(db.studyPilotDao().getTopicsForUser("u").first().none { it.sourceType == "IMPORTED_LMS" })
        val again = repo.importCourses("u", courses, "2026-11-28", skip = setOf("data structures"))
        assertEquals(0, again.subjects)
        assertTrue(db.studyPilotDao().getSubjectsForUser("u").first().isEmpty())
      }
      // A refused token says so instead of failing silently.
      val refused = runCatching { CanvasSource("$base/profile/settings", "revoked").courses() }.exceptionOrNull()
      assertTrue(refused?.message?.contains("refused") == true)
      // Moodle reports its own errors as JSON with "exception".
      server.removeContext("/webservice/rest/server.php")
      route("/webservice/rest/server.php") { """{"exception":"moodle_exception","message":"Invalid token"}""" }
      val moodle = runCatching { MoodleSource(base, "t").courses() }.exceptionOrNull()
      assertEquals("Moodle: Invalid token", moodle?.message)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun copiedPageAddressesAreTrimmedToTheSite() {
    assertEquals("https://canvas.vub.be", com.example.data.connect.LmsHttp.siteRoot("https://canvas.vub.be/profile/settings"))
    assertEquals("https://canvas.uni.edu", com.example.data.connect.LmsHttp.siteRoot("canvas.uni.edu/"))
    assertEquals("http://127.0.0.1:8080", com.example.data.connect.LmsHttp.siteRoot("http://127.0.0.1:8080/courses/7"))
    assertEquals("https://uni.edu/moodle", com.example.data.connect.LmsHttp.moodleRoot("https://uni.edu/moodle/login/index.php"))
    assertEquals("https://moodle.uni.edu", com.example.data.connect.LmsHttp.moodleRoot("moodle.uni.edu/my/"))
  }

  @Test
  fun htmlBecomesReadableText() {
    assertEquals("Intro\n\n- one\n- two & three", Html.toText("<h2>Intro</h2><ul><li>one</li><li>two &amp; three</li></ul><script>x()</script>"))
  }

  private suspend fun withRepo(block: suspend (StudyPilotRepository, AppDatabase) -> Unit) {
    val file = File.createTempFile("connections", ".db").also { it.delete() }
    val db = AppDatabase.configure(Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO))
    try {
      block(StudyPilotRepository(db.studyPilotDao()), db)
    } finally {
      db.close(); file.delete()
    }
  }
}
