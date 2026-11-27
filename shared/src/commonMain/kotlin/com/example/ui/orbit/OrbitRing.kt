package com.example.ui.orbit

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassColors
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** What the ring shows: everything planned, or only what was done. */
enum class OrbitView { Planned, Actual }

/** One band for everything, or the plan outside and what was done inside. */
enum class OrbitLayers { Unified, Layers }

private data class RingDrag(val block: DayBlock, val mode: DragMode, val last: Float, val delta: Float) {
  fun span(): Pair<Int, Int> = dragged(block, mode, delta.toInt(), step = 5, wrap = true)
}

/** Sizes of the ring, all in pixels, for a canvas of [size]. */
private class RingGeometry(size: Size, density: Float) {
  val center = Offset(size.width / 2f, size.height / 2f)
  val outer = min(size.width, size.height) / 2f - 6f * density
  val thickness = outer * 0.21f
  val inner = outer - thickness

  /** Planned arcs: the whole band, or its outer part when the layers are split. */
  fun plannedMid(layers: Boolean) = if (layers) outer - thickness * 0.29f else outer - thickness / 2f
  fun plannedWidth(layers: Boolean, density: Float) = if (layers) thickness * 0.5f else thickness - 7f * density
  val actualMid get() = inner + thickness * 0.22f
  fun actualWidth(density: Float) = thickness * 0.36f - 2f * density
}

private fun degreesOf(minute: Float) = minute / 4f - 90f

private fun pointAt(center: Offset, radius: Float, degrees: Float): Offset {
  val a = Math.toRadians(degrees.toDouble())
  return Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
}

/** The minute of the day under [pos], 00:00 at the top and running clockwise. */
private fun minuteAt(pos: Offset, center: Offset): Float {
  val deg = Math.toDegrees(atan2((pos.y - center.y).toDouble(), (pos.x - center.x).toDouble())).toFloat()
  return (((deg + 90f) % 360f) + 360f) % 360f * 4f
}

/** Shortest an arc is drawn, in minutes, so a quick session is still something to see and grab. */
private const val MIN_DRAWN_MINUTES = 18

/**
 * A ring segment with square ends and softly rounded corners, between radii [rIn] and [rOut], from
 * [from] degrees (canvas angles) through [sweep] degrees.
 */
private fun sectorPath(center: Offset, rIn: Float, rOut: Float, from: Float, sweep: Float, corner: Float): Path {
  val c = minOf(corner, (rOut - rIn) / 2f, sweep.toRadians() * rIn / 2f).coerceAtLeast(0f)
  val dOut = Math.toDegrees((c / rOut).toDouble()).toFloat()
  val dIn = Math.toDegrees((c / rIn).toDouble()).toFloat()
  val to = from + sweep
  fun rectOf(r: Float) = Rect(center.x - r, center.y - r, center.x + r, center.y + r)
  return Path().apply {
    val start = pointAt(center, rOut, from + dOut)
    moveTo(start.x, start.y)
    arcTo(rectOf(rOut), from + dOut, sweep - 2 * dOut, false)
    pointAt(center, rOut, to).let { k -> pointAt(center, rOut - c, to).let { e -> quadraticTo(k.x, k.y, e.x, e.y) } }
    pointAt(center, rIn + c, to).let { lineTo(it.x, it.y) }
    pointAt(center, rIn, to).let { k -> pointAt(center, rIn, to - dIn).let { e -> quadraticTo(k.x, k.y, e.x, e.y) } }
    arcTo(rectOf(rIn), to - dIn, -(sweep - 2 * dIn), false)
    pointAt(center, rIn, from).let { k -> pointAt(center, rIn + c, from).let { e -> quadraticTo(k.x, k.y, e.x, e.y) } }
    pointAt(center, rOut - c, from).let { lineTo(it.x, it.y) }
    pointAt(center, rOut, from).let { k -> pointAt(center, rOut, from + dOut).let { e -> quadraticTo(k.x, k.y, e.x, e.y) } }
    close()
  }
}

private fun Float.toRadians() = Math.toRadians(this.toDouble()).toFloat()

/** Start and sweep in canvas degrees for a block, widened to [MIN_DRAWN_MINUTES] around its middle. */
private fun arcSpan(start: Int, end: Int, progress: Float): Pair<Float, Float> {
  val minutes = maxOf(end - start, MIN_DRAWN_MINUTES).toFloat()
  val middle = (start + end) / 2f
  val from = if (end - start >= MIN_DRAWN_MINUTES) start.toFloat() else middle - minutes / 2
  return degreesOf(from) to minutes / 4f * progress
}

/**
 * Draws one block as a pastel segment. It's multiplied onto what's under it, so where two sessions
 * overlap their colours mix rather than one hiding the other.
 */
private fun DrawScope.drawBlockArc(
  start: Int,
  end: Int,
  center: Offset,
  radius: Float,
  width: Float,
  color: Color,
  progress: Float = 1f
) {
  val (from, sweep) = arcSpan(start, end, progress)
  if (sweep <= 0.01f) return
  drawPath(sectorPath(center, radius - width / 2, radius + width / 2, from, sweep, 6f * density), color, blendMode = BlendMode.Multiply)
}

/** A light outline round a block, for the one being hovered or dragged. */
private fun DrawScope.drawBlockOutline(start: Int, end: Int, center: Offset, radius: Float, width: Float, color: Color) {
  val (from, sweep) = arcSpan(start, end, 1f)
  drawPath(sectorPath(center, radius - width / 2, radius + width / 2, from, sweep, 6f * density), color, style = Stroke(2.2f * density))
}

private fun DrawScope.drawIconAt(painter: VectorPainter, at: Offset, size: Float, tint: Color) {
  translate(at.x - size / 2, at.y - size / 2) {
    with(painter) { draw(Size(size, size), colorFilter = ColorFilter.tint(tint)) }
  }
}

/** The recessed band the arcs sit on, with a light rim each side. */
internal fun DrawScope.drawOrbitTrack(center: Offset, outer: Float, thickness: Float, glass: GlassColors) {
  drawCircle(glass.inset, radius = outer - thickness / 2, center = center, style = Stroke(thickness))
  drawCircle(glass.insetRim, radius = outer, center = center, style = Stroke(1f * density))
}

/** A small ring for the week strip: the day's arcs only. */
internal fun DrawScope.drawMiniOrbit(blocks: List<DayBlock>, glass: GlassColors, progress: Float) {
  val center = Offset(size.width / 2, size.height / 2)
  val outer = min(size.width, size.height) / 2 - 1.5f * density
  val thickness = outer * 0.24f
  drawOrbitTrack(center, outer, thickness, glass)
  val mid = outer - thickness / 2
  val plan = colorPlanFor(blocks)
  for (b in blocks.filterNot { it.isMarker }.sortedByDescending { it.minutes }) {
    drawBlockArc(b.start, b.end, center, mid, thickness - 2.5f * density, plan.of(b).first, progress)
  }
  for (b in blocks.filter { it.isMarker }) {
    drawCircle(glass.text.copy(alpha = 0.7f), thickness * 0.28f, pointAt(center, mid, degreesOf(b.start.toFloat())))
  }
}

/**
 * The day as a 24-hour ring: each session an arc in its colour, 00:00 at the top. Drag an arc to
 * move it, or either of its ends to make it longer or shorter; click one to open it, or an empty
 * part of the ring to add a session there.
 */
@Composable
fun OrbitRing(
  blocks: List<DayBlock>,
  view: OrbitView,
  layers: OrbitLayers,
  glass: GlassColors,
  showNow: Boolean,
  dayLabel: String,
  modifier: Modifier = Modifier,
  onRetime: ((DayBlock, Int, Int) -> Unit)? = null,
  onOpen: (DayBlock) -> Unit = {},
  onAddAt: ((Int) -> Unit)? = null,
  plan: ColorPlan = remember(blocks) { colorPlanFor(blocks) }
) {
  val split = layers == OrbitLayers.Layers
  // On a phone the ring sits in a scrolling page, so arcs are picked up with a long press.
  val touchFirst = !com.example.ui.theme.LocalIsDesktop.current
  val canEdit = onRetime != null && view == OrbitView.Planned
  val now by produceState(nowMinutes()) {
    while (true) {
      delay(20_000)
      value = nowMinutes()
    }
  }
  val grow = remember { Animatable(0f) }
  LaunchedEffect(Unit) { grow.animateTo(1f, tween(650, easing = com.example.ui.theme.EaseOutStrong)) }

  var drag by remember { mutableStateOf<RingDrag?>(null) }
  var hover by remember { mutableStateOf<DayBlock?>(null) }
  val currentBlocks by rememberUpdatedState(blocks)
  val currentRetime by rememberUpdatedState(onRetime)
  val currentOpen by rememberUpdatedState(onOpen)
  val currentAdd by rememberUpdatedState(onAddAt)

  // Icons are drawn on the canvas, so their painters are made up front.
  val iconSet = remember { listOf(Icons.Default.Check) + OrbitIconSet }
  val painters: Map<ImageVector, VectorPainter> = iconSet.associateWith { rememberVectorPainter(it) }

  /** The block under [pos] on the planned band, and how dragging it from there would change it. */
  fun hitTest(pos: Offset, size: Size, density: Float): Pair<DayBlock, DragMode>? {
    val g = RingGeometry(size, density)
    val mid = g.plannedMid(split)
    val width = g.plannedWidth(split, density)
    val distance = hypot(pos.x - g.center.x, pos.y - g.center.y)
    if (distance < mid - width / 2 - 6 * density || distance > mid + width / 2 + 8 * density) return null
    val minute = minuteAt(pos, g.center)
    val pool = if (view == OrbitView.Actual) currentBlocks.filter { it.done } else currentBlocks
    fun covers(b: DayBlock, m: Float): Boolean {
      val pad = 2f + maxOf(0, MIN_DRAWN_MINUTES - b.minutes) / 2f
      return m >= b.start - pad && m <= b.end + pad
    }
    val hit = pool.filter { b -> covers(b, minute) || covers(b, minute + DAY_MINUTES) }.minByOrNull { it.minutes } ?: return null
    val m = if (covers(hit, minute + DAY_MINUTES)) minute + DAY_MINUTES else minute
    // The last few minutes at each end resize; the rest moves.
    val edge = minOf(12f, hit.minutes / 4f)
    val mode = when {
      hit.isMarker || hit.minutes < 40 -> DragMode.Move
      m - hit.start < edge -> DragMode.ResizeStart
      hit.end - m < edge -> DragMode.ResizeEnd
      else -> DragMode.Move
    }
    return hit to mode
  }

  BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
    val ringDp = min(maxWidth.value, maxHeight.value)
    Canvas(
      Modifier
        .fillMaxSize()
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .pointerHoverIcon(if (hover != null || drag != null) PointerIcon.Hand else PointerIcon.Default)
        .pointerInput(view, split) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              val pos = event.changes.firstOrNull()?.position ?: continue
              hover = if (event.type == PointerEventType.Exit) null
              else hitTest(pos, Size(size.width.toFloat(), size.height.toFloat()), density)?.first
            }
          }
        }
        .pointerInput(view, split) {
          detectTapGestures { pos ->
            val area = Size(size.width.toFloat(), size.height.toFloat())
            val hit = hitTest(pos, area, density)
            if (hit != null) {
              currentOpen(hit.first)
            } else {
              val g = RingGeometry(area, density)
              val distance = hypot(pos.x - g.center.x, pos.y - g.center.y)
              val add = currentAdd
              if (add != null && distance in g.inner..g.outer) add(snap(minuteAt(pos, g.center).toInt(), 15) % DAY_MINUTES)
            }
          }
        }
        .pointerInput(view, split, canEdit, touchFirst) {
          if (!canEdit) return@pointerInput
          val pickUp: suspend androidx.compose.ui.input.pointer.PointerInputScope.(
            (Offset) -> Unit, (androidx.compose.ui.input.pointer.PointerInputChange, Offset) -> Unit, () -> Unit, () -> Unit
          ) -> Unit = if (touchFirst) { s, d, e, c -> detectDragGesturesAfterLongPress(onDragStart = s, onDrag = d, onDragEnd = e, onDragCancel = c) }
          else { s, d, e, c -> detectDragGestures(onDragStart = s, onDrag = d, onDragEnd = e, onDragCancel = c) }
          pickUp(
            { pos ->
              val area = Size(size.width.toFloat(), size.height.toFloat())
              drag = hitTest(pos, area, density)?.let { (block, mode) ->
                RingDrag(block, mode, minuteAt(pos, RingGeometry(area, density).center), 0f)
              }
            },
            { change, _ ->
              val d = drag ?: return@pickUp
              change.consume()
              val area = Size(size.width.toFloat(), size.height.toFloat())
              val minute = minuteAt(change.position, RingGeometry(area, density).center)
              var diff = minute - d.last
              // Crossing 00:00 at the top shouldn't jump a whole day.
              if (diff > DAY_MINUTES / 2) diff -= DAY_MINUTES
              if (diff < -DAY_MINUTES / 2) diff += DAY_MINUTES
              drag = d.copy(last = minute, delta = d.delta + diff)
            },
            {
              drag?.let { d ->
                val (s, e) = d.span()
                if (s != d.block.start || e != d.block.end) currentRetime?.invoke(d.block, s, e)
              }
              drag = null
            },
            { drag = null }
          )
        }
    ) {
      val g = RingGeometry(size, density)
      val progress = grow.value

      // Soft glow inside the ring and a faint halo outside it.
      drawCircle(
        Brush.radialGradient(
          listOf(Color.White.copy(alpha = if (glass.dark) 0.07f else 0.55f), Color.Transparent),
          center = g.center,
          radius = g.inner
        ),
        radius = g.inner - 2 * density,
        center = g.center
      )
      drawOrbitTrack(g.center, g.outer, g.thickness, glass)

      val pMid = g.plannedMid(split)
      val pWidth = g.plannedWidth(split, density)
      val iconSize = min(pWidth * 0.5f, 18 * density)
      val dragged = drag
      val outline = if (glass.dark) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.95f)

      val ordered = blocks.filterNot { it.isMarker }.sortedByDescending { it.minutes }
      fun spanOf(b: DayBlock) = dragged?.takeIf { it.block == b }?.span() ?: (b.start to b.end)
      fun ghost(b: DayBlock) = view == OrbitView.Actual && !b.done
      // Colours first, multiplied together where sessions overlap; then outlines and icons on top.
      for (b in ordered) {
        val (s, e) = spanOf(b)
        val tint = plan.of(b).first
        drawBlockArc(s, e, g.center, pMid, pWidth, if (ghost(b)) tint.copy(alpha = 0.28f) else tint, progress)
      }
      for (b in ordered) {
        val (s, e) = spanOf(b)
        if (dragged?.block == b || hover == b) drawBlockOutline(s, e, g.center, pMid, pWidth, outline)
        val arcLength = maxOf(e - s, MIN_DRAWN_MINUTES) / 4f * (Math.PI.toFloat() / 180f) * pMid
        if (!ghost(b) && progress > 0.6f && arcLength >= iconSize + 6 * density) {
          val icon = if (view == OrbitView.Actual) Icons.Default.Check else iconFor(b.session)
          val at = pointAt(g.center, pMid, degreesOf((s + e) / 2f))
          drawIconAt(painters.getValue(icon), at, iconSize, plan.of(b).second.copy(alpha = ((progress - 0.6f) / 0.4f).coerceIn(0f, 1f)))
        }
      }
      // Alarms over everything.
      for (b in blocks.filter { it.isMarker }) {
        val moving = dragged?.takeIf { it.block == b }
        val at = pointAt(g.center, pMid, degreesOf((moving?.span()?.first ?: b.start).toFloat()))
        val r = pWidth * 0.36f
        if (moving != null || hover == b) drawCircle(outline, r + 2.5f * density, at)
        drawCircle(glass.frost, r, at)
        drawCircle(glass.text.copy(alpha = 0.55f), r, at, style = Stroke(1.4f * density))
        if (progress > 0.6f) drawIconAt(painters.getValue(iconFor(b.session)), at, r * 1.15f, glass.text.copy(alpha = ((progress - 0.6f) / 0.4f).coerceIn(0f, 1f)))
      }

      if (split) {
        // Inner layer: what was actually done.
        val aWidth = g.actualWidth(density)
        for (b in blocks.filter { it.done }.sortedByDescending { it.minutes }) {
          val (tint, ink) = plan.of(b)
          drawBlockArc(b.start, b.end, g.center, g.actualMid, aWidth, tint, progress)
          if (aWidth > 12 * density) {
            drawIconAt(painters.getValue(Icons.Default.Check), pointAt(g.center, g.actualMid, degreesOf((b.start + b.end) / 2f)), aWidth * 0.55f, ink)
          }
        }
      }

      if (showNow) {
        val at = pointAt(g.center, pMid, degreesOf(now.toFloat()))
        drawCircle(glass.ink.copy(alpha = 0.18f), radius = 9.5f * density, center = at)
        drawCircle(Color.White, radius = 6.5f * density, center = at)
        drawCircle(glass.ink, radius = 4f * density, center = at)
      }
    }

    // The readout in the middle.
    val big = (ringDp * 0.1f).coerceIn(18f, 46f).sp
    val small = (ringDp * 0.038f).coerceIn(10.5f, 15f).sp
    val dragging = drag
    val shown = hover
    val (headline, line1, line2) = when {
      dragging != null -> {
        val (s, e) = dragging.span()
        Triple(
          clockLabel(if (dragging.mode == DragMode.ResizeEnd) e else s),
          if (dragging.block.isMarker) dragging.block.session.topic else "${clockLabel(s)} – ${clockLabel(e)} · ${formatDuration(e - s)}",
          "Release to save"
        )
      }
      shown != null -> Triple(
        if (shown.isMarker) clockLabel(shown.start) else formatDuration(shown.minutes),
        shown.session.topic.ifBlank { groupLabel(shown.session) },
        if (shown.isMarker) "Alarm" else "${clockLabel(shown.start)} – ${clockLabel(shown.end)}" + if (shown.done) " · done" else ""
      )
      showNow -> Triple(
        clockLabel(now),
        blockAt(blocks, now)?.let { it.session.topic.ifBlank { groupLabel(it.session) } } ?: "Open time",
        dayLabel
      )
      else -> Triple(formatDuration(coveredMinutes(blocks)), "planned", dayLabel)
    }
    Column(
      Modifier.widthIn(max = (ringDp * 0.5f).dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Text(
        headline,
        style = MaterialTheme.typography.displaySmall.copy(fontSize = big, lineHeight = big),
        color = glass.text,
        maxLines = 1
      )
      Text(
        line1,
        fontSize = small,
        fontWeight = FontWeight.SemiBold,
        color = glass.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
      )
      Text(line2, fontSize = small * 0.92f, color = glass.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
  }
}
