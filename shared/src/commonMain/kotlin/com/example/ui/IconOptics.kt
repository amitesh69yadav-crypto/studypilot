package com.example.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.math.sqrt

/**
 * Where an icon actually draws inside its viewport. Material icons fill their 24-unit box by
 * different amounts (a clock is 20 wide, a trend arrow 20×12), so equal sizes don't look equal.
 */
fun ImageVector.drawnBounds(): Rect {
  var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
  fun add(px: Float, py: Float) { l = minOf(l, px); t = minOf(t, py); r = maxOf(r, px); b = maxOf(b, py) }
  fun walk(node: VectorNode) {
    when (node) {
      is VectorGroup -> node.forEach { walk(it) }
      is VectorPath -> {
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        fun to(px: Float, py: Float) { x = px; y = py; add(x, y) }
        for (c in node.pathData) when (c) {
          is PathNode.MoveTo -> { to(c.x, c.y); sx = x; sy = y }
          is PathNode.RelativeMoveTo -> { to(x + c.dx, y + c.dy); sx = x; sy = y }
          is PathNode.LineTo -> to(c.x, c.y)
          is PathNode.RelativeLineTo -> to(x + c.dx, y + c.dy)
          is PathNode.HorizontalTo -> to(c.x, y)
          is PathNode.RelativeHorizontalTo -> to(x + c.dx, y)
          is PathNode.VerticalTo -> to(x, c.y)
          is PathNode.RelativeVerticalTo -> to(x, y + c.dy)
          is PathNode.CurveTo -> to(c.x3, c.y3)
          is PathNode.RelativeCurveTo -> to(x + c.dx3, y + c.dy3)
          is PathNode.ReflectiveCurveTo -> to(c.x2, c.y2)
          is PathNode.RelativeReflectiveCurveTo -> to(x + c.dx2, y + c.dy2)
          is PathNode.QuadTo -> to(c.x2, c.y2)
          is PathNode.RelativeQuadTo -> to(x + c.dx2, y + c.dy2)
          is PathNode.ReflectiveQuadTo -> to(c.x, c.y)
          is PathNode.RelativeReflectiveQuadTo -> to(x + c.dx, y + c.dy)
          is PathNode.ArcTo -> {
            val mx = (x + c.arcStartX) / 2; val my = (y + c.arcStartY) / 2; val rr = c.horizontalEllipseRadius
            add(mx - rr, my - rr); add(mx + rr, my + rr); to(c.arcStartX, c.arcStartY)
          }
          is PathNode.RelativeArcTo -> {
            val ex = x + c.arcStartDx; val ey = y + c.arcStartDy
            val mx = (x + ex) / 2; val my = (y + ey) / 2; val rr = c.horizontalEllipseRadius
            add(mx - rr, my - rr); add(mx + rr, my + rr); to(ex, ey)
          }
          is PathNode.Close -> { x = sx; y = sy }
        }
      }
    }
  }
  walk(root)
  return if (r < l) Rect(0f, 0f, viewportWidth, viewportHeight) else Rect(l, t, r, b)
}

/**
 * Scales and centres an icon so its drawn shape has the same visual size as its neighbours:
 * the average of its area-equivalent side and its longest side is brought to [target] of the box.
 */
fun Modifier.opticalSize(icon: ImageVector, target: Float = 0.75f): Modifier {
  val box = icon.drawnBounds()
  val vw = icon.viewportWidth; val vh = icon.viewportHeight
  val visual = (sqrt(box.width * box.height) + maxOf(box.width, box.height)) / 2f
  val scale = (target * minOf(vw, vh) / visual).coerceIn(0.6f, 1.25f)
  val dx = (vw / 2f - box.center.x) / vw
  val dy = (vh / 2f - box.center.y) / vh
  return graphicsLayer {
    scaleX = scale; scaleY = scale
    translationX = dx * size.width * scale
    translationY = dy * size.height * scale
  }
}
