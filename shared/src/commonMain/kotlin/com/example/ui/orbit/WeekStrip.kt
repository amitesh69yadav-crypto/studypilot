package com.example.ui.orbit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TimetableSession
import com.example.ui.theme.GlassColors
import com.example.ui.theme.LocalStudyColors
import com.example.ui.theme.glass

/**
 * The week as seven small orbits, Monday first, each with the time planned that day. The selected day
 * sits on a glass card; today's label is in the accent colour.
 */
@Composable
fun WeekOrbitStrip(
  sessions: List<TimetableSession>,
  week: List<String>,
  selected: String,
  glass: GlassColors,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val today = todayDate()
  val accent = LocalStudyColors.current.deepSage
  val grow = remember { Animatable(0f) }
  LaunchedEffect(week.first()) {
    grow.snapTo(0f)
    grow.animateTo(1f, tween(600, easing = com.example.ui.theme.EaseOutStrong))
  }
  androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
  // On a phone each day is narrow: shorter labels and just the hours in the middle.
  val compact = maxWidth < 560.dp
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp)) {
    week.forEach { date ->
      val blocks = remember(sessions, date) { blocksOn(sessions, date) }
      val planned = remember(blocks) { coveredMinutes(blocks) }
      val isSelected = date == selected
      val isToday = date == today
      val shape = RoundedCornerShape(22.dp)
      val hover = remember { MutableInteractionSource() }
      val hovered by hover.collectIsHoveredAsState()
      Column(
        Modifier
          .weight(1f)
          .clip(shape)
          .then(
            if (isSelected) Modifier.glass(shape, glass, glass.selected)
            else if (hovered) Modifier.glass(shape, glass, glass.inset)
            else Modifier
          )
          .hoverable(hover)
          .pointerHoverIcon(PointerIcon.Hand)
          .clickable(interactionSource = hover, indication = null) { onSelect(date) }
          .padding(vertical = if (compact) 8.dp else 12.dp, horizontal = if (compact) 2.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          if (isToday && !compact) "${weekdayShort(date)} · TODAY" else weekdayShort(date),
          fontSize = 10.5.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.1.sp,
          color = if (isToday) accent else glass.muted,
          maxLines = 1
        )
        Text(
          if (compact) date.takeLast(2).trimStart('0') else monthDay(date),
          fontSize = 12.5.sp,
          fontWeight = FontWeight.SemiBold,
          color = if (isToday) accent else glass.text,
          maxLines = 1
        )
        Spacer(Modifier.height(8.dp))
        Box(
          Modifier
            .widthIn(max = 116.dp)
            .fillMaxWidth(0.86f)
            .aspectRatio(1f)
            .graphicsLayer {
              val s = if (hovered && !isSelected) 1.04f else 1f
              scaleX = s
              scaleY = s
            },
          contentAlignment = Alignment.Center
        ) {
          Canvas(Modifier.fillMaxSize()) { drawMiniOrbit(blocks, glass, grow.value) }
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              if (planned == 0) "—" else if (compact) "${(planned + 30) / 60}h" else formatDuration(planned),
              fontSize = if (compact) 11.sp else 13.sp,
              fontWeight = FontWeight.Bold,
              color = glass.text,
              maxLines = 1
            )
            if (!compact) Text("planned", fontSize = 10.sp, color = glass.muted)
          }
        }
      }
    }
  }
  }
}
