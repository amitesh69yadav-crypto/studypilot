package com.example.sync

import com.example.data.model.TimetableSession
import com.example.ui.orbit.DAY_MINUTES
import com.example.ui.orbit.DayBlock
import com.example.ui.orbit.DragMode
import com.example.ui.orbit.blockAt
import com.example.ui.orbit.blocksOn
import com.example.ui.orbit.columnBlocksOn
import com.example.ui.orbit.coveredMinutes
import com.example.ui.orbit.dragged
import com.example.ui.orbit.formatDuration
import com.example.ui.orbit.lanesFor
import com.example.ui.orbit.legendFor
import com.example.ui.orbit.occursOn
import com.example.ui.orbit.retimed
import com.example.ui.orbit.snap
import com.example.ui.orbit.weekOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbitModelTest {
  private fun session(
    id: Long,
    start: String,
    end: String,
    date: String = "2026-11-23",
    days: String = "",
    category: String = "ACADEMIC",
    subject: String = "Physics",
    status: String = "SCHEDULED"
  ) = TimetableSession(
    id = id, subjectId = 1, subjectName = subject, topic = "t$id", date = date, startTime = start, endTime = end,
    recurringDays = days, sessionCategory = category, status = status
  )

  @Test
  fun weekStartsOnMondayEvenFromASunday() {
    val expected = listOf("2026-11-23", "2026-11-24", "2026-11-25", "2026-11-26", "2026-11-27", "2026-11-28", "2026-11-29")
    assertEquals(expected, weekOf("2026-11-29"))
    assertEquals(expected, weekOf("2026-11-23"))
    assertEquals(expected, weekOf("2026-11-27"))
  }

  @Test
  fun repeatingSessionsShowOnTheirWeekdaysFromTheirStartDate() {
    val s = session(1, "09:00", "10:00", date = "2026-11-25", days = "Mon,Wed,Fri")
    assertTrue(occursOn(s, "2026-11-25")) // its own date, a Wednesday
    assertTrue(occursOn(s, "2026-11-27")) // Friday
    assertTrue(occursOn(s, "2026-11-30")) // the next Monday
    assertFalse(occursOn(s, "2026-11-23")) // a Monday before it started
    assertFalse(occursOn(s, "2026-11-26")) // Thursday
    assertFalse(occursOn(session(2, "09:00", "10:00", date = "2026-11-25"), "2026-11-26"))
  }

  @Test
  fun alarmsAreMarkersAndNightSessionsRunPastMidnight() {
    val blocks = blocksOn(
      listOf(session(1, "23:00", "07:00", category = "SLEEP"), session(2, "06:30", ""), session(3, "13:00", "14:30")),
      "2026-11-23"
    )
    assertEquals(listOf(2L, 3L, 1L), blocks.map { it.session.id })
    val alarm = blocks[0]
    assertTrue(alarm.isMarker)
    assertEquals(390 to 405, alarm.start to alarm.end)
    val sleep = blocks[2]
    assertEquals(23 * 60 to (24 + 7) * 60, sleep.start to sleep.end)
    assertEquals(8 * 60, sleep.minutes)
  }

  @Test
  fun coveredTimeCountsOverlapsOnceAndWrapsMidnight() {
    val blocks = blocksOn(
      listOf(
        session(1, "09:00", "11:00"),
        session(2, "10:00", "12:00"),
        session(3, "23:00", "01:00"),
        session(4, "00:30", "02:00"),
        session(5, "08:00", "")
      ),
      "2026-11-23"
    )
    // 09-12 is 3h; 23:00-01:00 and 00:30-02:00 together cover 23:00-02:00, 3h. The alarm adds nothing.
    assertEquals(6 * 60, coveredMinutes(blocks))
    assertEquals(0, coveredMinutes(emptyList()))
  }

  @Test
  fun legendGroupsBySubjectAndRoutineLargestFirst() {
    val rows = legendFor(
      blocksOn(
        listOf(
          session(1, "09:00", "10:00", subject = "Maths"),
          session(2, "11:00", "11:45", subject = "Maths"),
          session(3, "22:00", "06:00", category = "SLEEP"),
          session(4, "13:00", "13:30", category = "FOOD")
        ),
        "2026-11-23"
      )
    )
    assertEquals(listOf("Sleep", "Maths", "Meals"), rows.map { it.label })
    assertEquals(listOf(480, 105, 30), rows.map { it.minutes })
    assertEquals(33.3, rows[0].percentOfDay, 0.05)
  }

  @Test
  fun draggingSnapsKeepsAMinimumAndWrapsOnlyOnTheRing() {
    val block = DayBlock(session(1, "09:00", "10:00"), 540, 600, isMarker = false)
    assertEquals(560 to 620, dragged(block, DragMode.Move, 22))
    assertEquals(0 to 60, dragged(block, DragMode.Move, -1000))
    assertEquals(1380 to 1440, dragged(block, DragMode.Move, 840, wrap = true))
    assertEquals(60 to 120, dragged(block, DragMode.Move, 960, wrap = true)) // round past midnight
    assertEquals(540 to 555, dragged(block, DragMode.ResizeEnd, -500))
    assertEquals(585 to 600, dragged(block, DragMode.ResizeStart, 500))
    assertEquals(540 to 720, dragged(block, DragMode.ResizeEnd, 120))
    assertEquals(540 to 1440, dragged(block, DragMode.ResizeEnd, 5000))
    assertEquals(15, snap(13, 15))
    assertEquals(-15, snap(-10, 15))
  }

  @Test
  fun retimingWritesClockTimesAndWrapsPastMidnight() {
    val block = DayBlock(session(1, "22:00", "23:00"), 1320, 1380, isMarker = false)
    val moved = retimed(block, 1380, 1500)
    assertEquals("23:00" to "01:00", moved.startTime to moved.endTime)
    val toMidnight = retimed(block, 1320, DAY_MINUTES)
    assertEquals("00:00", toMidnight.endTime)
    // Read back, an end at 00:00 runs to the end of the day rather than being empty.
    assertEquals(1440, blocksOn(listOf(toMidnight), "2026-11-23").single().end)
    val alarm = DayBlock(session(2, "07:00", ""), 420, 435, isMarker = true)
    assertEquals("07:30" to "", retimed(alarm, 450, 465).let { it.startTime to it.endTime })
  }

  @Test
  fun dayColumnsCarryTheMorningOfLastNightsSleep() {
    val sleep = session(1, "23:00", "07:00", date = "2026-11-23", category = "SLEEP")
    val tuesday = columnBlocksOn(listOf(sleep), "2026-11-24")
    assertEquals(1, tuesday.size)
    assertTrue(tuesday[0].fromYesterday)
    assertEquals(0 to 420, tuesday[0].start to tuesday[0].end)
  }

  @Test
  fun overlappingBlocksGetSideBySideLanes() {
    val blocks = blocksOn(
      listOf(session(1, "09:00", "11:00"), session(2, "10:00", "12:00"), session(3, "10:30", "10:45"), session(4, "13:00", "14:00")),
      "2026-11-23"
    )
    val lanes = lanesFor(blocks)
    val byId = blocks.associateBy { it.session.id }
    assertEquals(0 to 3, lanes[byId[1L]])
    assertEquals(1 to 3, lanes[byId[2L]])
    assertEquals(2 to 3, lanes[byId[3L]])
    assertEquals(0 to 1, lanes[byId[4L]])
  }

  @Test
  fun theBlockNowIsTheShortestOneCoveringThisMinute() {
    val blocks = blocksOn(listOf(session(1, "22:00", "08:00", category = "SLEEP"), session(2, "07:00", "07:30")), "2026-11-23")
    assertEquals(2L, blockAt(blocks, 7 * 60 + 10)?.session?.id)
    assertEquals(1L, blockAt(blocks, 3 * 60)?.session?.id)
    assertNull(blockAt(blocks, 12 * 60))
    assertEquals("7h 30m", formatDuration(450))
    assertEquals("45m", formatDuration(45))
  }
}
