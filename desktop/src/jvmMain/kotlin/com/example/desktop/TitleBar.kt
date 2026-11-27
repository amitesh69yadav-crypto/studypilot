package com.example.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.example.ui.theme.LocalGlass
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle

/** Height of the window's own title strip; the app sits right below it. */
val TitleBarHeight: Dp = 34.dp

/**
 * The window has no system frame, so the app's painted background runs right up to the top edge.
 * This strip gives back what the frame did: drag to move, double-click to maximise, and the
 * minimise / maximise / close buttons.
 */
@Composable
fun FrameWindowScope.TitleBar(state: WindowState, onClose: () -> Unit) {
  val glass = LocalGlass.current
  val iconTint = glass?.text ?: Color(0xFF1B1F1A)
  val maximized = state.placement == WindowPlacement.Maximized
  Row(Modifier.fillMaxWidth().height(TitleBarHeight)) {
    // Drag strip: everything left of the buttons.
    Box(
      Modifier
        .weight(1f)
        .fillMaxHeight()
        .pointerInput(Unit) {
          // One handler for both: a second press soon after the first maximises or restores;
          // otherwise moving past the drag threshold moves the window.
          var lastPress = 0L
          awaitEachGesture {
            val down = awaitFirstDown()
            val now = System.currentTimeMillis()
            if (now - lastPress < 450) {
              lastPress = 0L
              toggleMaximize(window, state)
              return@awaitEachGesture
            }
            lastPress = now
            val pressAt = MouseInfo.getPointerInfo().location
            var dragging = false
            var grab = Point()
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
              val mouse = MouseInfo.getPointerInfo().location
              if (!dragging && pressAt.distance(mouse) > 4) {
                dragging = true
                lastPress = 0L
                if (state.placement == WindowPlacement.Maximized) {
                  // Dragging a maximised window restores it under the cursor, like the system frame does.
                  val widthBefore = window.width.toDouble()
                  val ratio = (mouse.x - window.x) / widthBefore
                  state.placement = WindowPlacement.Floating
                  window.setLocation(mouse.x - (window.width * ratio).toInt(), mouse.y - 12)
                }
                grab = Point(mouse.x - window.x, mouse.y - window.y)
              }
              if (dragging) {
                change.consume()
                window.setLocation(mouse.x - grab.x, mouse.y - grab.y)
              }
            }
          }
        }
    )
    CaptionButton(Icons.Default.Remove, "Minimize", iconTint) { state.isMinimized = true }
    CaptionButton(if (maximized) Icons.Default.FilterNone else Icons.Default.CropSquare, if (maximized) "Restore" else "Maximize", iconTint, iconSize = if (maximized) 14.dp else 15.dp) {
      toggleMaximize(window, state)
    }
    CaptionButton(Icons.Default.Close, "Close", iconTint, danger = true, onClick = onClose)
  }
}

private fun toggleMaximize(window: java.awt.Frame, state: WindowState) {
  if (state.placement == WindowPlacement.Maximized) {
    state.placement = WindowPlacement.Floating
  } else {
    // Without a frame, maximising would cover the taskbar; keep to the usable screen area.
    val screen = window.graphicsConfiguration
    val bounds: Rectangle = screen.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(screen)
    window.maximizedBounds = Rectangle(0, 0, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
      .also { it.setLocation(insets.left, insets.top) }
    state.placement = WindowPlacement.Maximized
  }
}

@Composable
private fun CaptionButton(
  icon: ImageVector,
  label: String,
  tint: Color,
  danger: Boolean = false,
  iconSize: Dp = 16.dp,
  onClick: () -> Unit
) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val glass = LocalGlass.current
  val hoverFill = when {
    danger -> Color(0xFFC42B1C)
    glass?.dark == true -> Color.White.copy(alpha = 0.10f)
    else -> Color.Black.copy(alpha = 0.07f)
  }
  Box(
    Modifier
      .width(46.dp)
      .fillMaxHeight()
      .background(if (hovered) hoverFill else Color.Transparent)
      .hoverable(interaction)
      .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Icon(icon, contentDescription = label, tint = if (hovered && danger) Color.White else tint, modifier = Modifier.size(iconSize))
  }
}

/**
 * Thin edges and corners that resize the frameless window. Only active while it isn't maximised.
 */
@Composable
fun FrameWindowScope.ResizeEdges(state: WindowState, thickness: Dp = 5.dp) {
  if (state.placement != WindowPlacement.Floating) return
  val w = window
  Box(Modifier.fillMaxSize()) {
    Edge(w, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(thickness), Cursor.N_RESIZE_CURSOR, top = true)
    Edge(w, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(thickness), Cursor.S_RESIZE_CURSOR, bottom = true)
    Edge(w, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(thickness), Cursor.W_RESIZE_CURSOR, left = true)
    Edge(w, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(thickness), Cursor.E_RESIZE_CURSOR, right = true)
    val corner = thickness * 2
    Edge(w, Modifier.align(Alignment.TopStart).size(corner), Cursor.NW_RESIZE_CURSOR, top = true, left = true)
    Edge(w, Modifier.align(Alignment.TopEnd).size(corner), Cursor.NE_RESIZE_CURSOR, top = true, right = true)
    Edge(w, Modifier.align(Alignment.BottomStart).size(corner), Cursor.SW_RESIZE_CURSOR, bottom = true, left = true)
    Edge(w, Modifier.align(Alignment.BottomEnd).size(corner), Cursor.SE_RESIZE_CURSOR, bottom = true, right = true)
  }
}

@Composable
private fun Edge(
  window: java.awt.Window,
  modifier: Modifier,
  cursor: Int,
  top: Boolean = false,
  bottom: Boolean = false,
  left: Boolean = false,
  right: Boolean = false
) {
  Box(
    modifier
      .pointerHoverIcon(PointerIcon(Cursor(cursor)))
      .pointerInput(Unit) {
        var start = Rectangle()
        var mouseStart = Point()
        detectDragGestures(
          onDragStart = { start = window.bounds; mouseStart = MouseInfo.getPointerInfo().location },
          onDrag = { change, _ ->
            change.consume()
            val m = MouseInfo.getPointerInfo().location
            val dx = m.x - mouseStart.x; val dy = m.y - mouseStart.y
            val min = window.minimumSize
            var x = start.x; var y = start.y; var w = start.width; var h = start.height
            if (right) w = maxOf(min.width, start.width + dx)
            if (bottom) h = maxOf(min.height, start.height + dy)
            if (left) { w = maxOf(min.width, start.width - dx); x = start.x + start.width - w }
            if (top) { h = maxOf(min.height, start.height - dy); y = start.y + start.height - h }
            window.setBounds(x, y, w, h)
          }
        )
      }
  )
}
