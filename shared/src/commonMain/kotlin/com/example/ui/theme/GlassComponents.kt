package com.example.ui.theme

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/*
 * Drop-in versions of the Material cards, buttons and chips. On the phone ([AppStyle.Classic]) each
 * one is the Material component, unchanged. On the desktop ([AppStyle.Glass]) cards become frosted
 * glass with a light rim, buttons become pills (the primary one near-black), and chips become pills
 * on a glass track. Screens import these instead of the Material ones and need no other change.
 */

@Composable
fun Card(
  modifier: Modifier = Modifier,
  shape: Shape = CardDefaults.shape,
  colors: CardColors = CardDefaults.cardColors(),
  elevation: CardElevation = CardDefaults.cardElevation(),
  border: BorderStroke? = null,
  content: @Composable ColumnScope.() -> Unit
) {
  val glass = LocalGlass.current
  if (glass == null) {
    androidx.compose.material3.Card(modifier, shape, colors, elevation, border, content)
  } else {
    val glassShape = glassCardShape(shape)
    androidx.compose.material3.Card(
      modifier = modifier.glass(glassShape, glass, glassFill(colors.containerColor, glass)),
      shape = glassShape,
      colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = colors.contentColor),
      elevation = CardDefaults.cardElevation(0.dp),
      border = border,
      content = content
    )
  }
}

@Composable
fun Card(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  shape: Shape = CardDefaults.shape,
  colors: CardColors = CardDefaults.cardColors(),
  elevation: CardElevation = CardDefaults.cardElevation(),
  border: BorderStroke? = null,
  interactionSource: MutableInteractionSource? = null,
  content: @Composable ColumnScope.() -> Unit
) {
  val glass = LocalGlass.current
  // One source for the click, the hover tint and the press-in, so they move together.
  val source = interactionSource ?: androidx.compose.runtime.remember { MutableInteractionSource() }
  if (glass == null) {
    androidx.compose.material3.Card(onClick, modifier, enabled, shape, colors, elevation, border, interactionSource, content)
  } else {
    val glassShape = glassCardShape(shape)
    androidx.compose.material3.Card(
      onClick = onClick,
      modifier = modifier.pressScale(source).glass(glassShape, glass, glassFill(colors.containerColor, glass)),
      enabled = enabled,
      shape = glassShape,
      colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = colors.contentColor),
      elevation = CardDefaults.cardElevation(0.dp),
      border = border,
      interactionSource = source,
      content = content
    )
  }
}

@Composable
fun ElevatedCard(
  modifier: Modifier = Modifier,
  shape: Shape = CardDefaults.elevatedShape,
  colors: CardColors = CardDefaults.elevatedCardColors(),
  elevation: CardElevation = CardDefaults.elevatedCardElevation(),
  content: @Composable ColumnScope.() -> Unit
) {
  if (LocalGlass.current == null) androidx.compose.material3.ElevatedCard(modifier, shape, colors, elevation, content)
  else Card(modifier, shape, colors, elevation, null, content)
}

@Composable
fun OutlinedCard(
  modifier: Modifier = Modifier,
  shape: Shape = CardDefaults.outlinedShape,
  colors: CardColors = CardDefaults.outlinedCardColors(),
  elevation: CardElevation = CardDefaults.outlinedCardElevation(),
  border: BorderStroke = CardDefaults.outlinedCardBorder(),
  content: @Composable ColumnScope.() -> Unit
) {
  if (LocalGlass.current == null) androidx.compose.material3.OutlinedCard(modifier, shape, colors, elevation, border, content)
  else Card(modifier, shape, colors, elevation, null, content)
}

@Composable
fun Button(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  shape: Shape = ButtonDefaults.shape,
  colors: ButtonColors = ButtonDefaults.buttonColors(),
  elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
  border: BorderStroke? = null,
  contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
  interactionSource: MutableInteractionSource? = null,
  content: @Composable RowScope.() -> Unit
) {
  val glass = LocalGlass.current
  // One source for the click, the hover tint and the press-in, so they move together.
  val source = interactionSource ?: androidx.compose.runtime.remember { MutableInteractionSource() }
  if (glass == null) {
    androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
  } else {
    androidx.compose.material3.Button(
      onClick = onClick,
      modifier = modifier.pressScale(source).defaultMinSize(minHeight = 40.dp),
      enabled = enabled,
      shape = CircleShape,
      colors = colors,
      elevation = null,
      border = border,
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
      interactionSource = source,
      content = content
    )
  }
}

@Composable
fun OutlinedButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  shape: Shape = ButtonDefaults.outlinedShape,
  colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
  elevation: ButtonElevation? = null,
  border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
  contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
  interactionSource: MutableInteractionSource? = null,
  content: @Composable RowScope.() -> Unit
) {
  val glass = LocalGlass.current
  // One source for the click, the hover tint and the press-in, so they move together.
  val source = interactionSource ?: androidx.compose.runtime.remember { MutableInteractionSource() }
  if (glass == null) {
    androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
  } else {
    // A recessed glass pill, like the site's secondary actions.
    androidx.compose.material3.OutlinedButton(
      onClick = onClick,
      modifier = modifier.pressScale(source).defaultMinSize(minHeight = 40.dp),
      enabled = enabled,
      shape = CircleShape,
      colors = ButtonDefaults.outlinedButtonColors(
        containerColor = glass.inset,
        contentColor = if (colors.contentColor == MaterialTheme.colorScheme.primary) glass.text else colors.contentColor,
        disabledContentColor = glass.muted.copy(alpha = 0.5f)
      ),
      elevation = null,
      border = BorderStroke(0.8.dp, glass.insetRim),
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
      interactionSource = source,
      content = content
    )
  }
}

@Composable
fun FilterChip(
  selected: Boolean,
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  leadingIcon: @Composable (() -> Unit)? = null,
  trailingIcon: @Composable (() -> Unit)? = null,
  shape: Shape = FilterChipDefaults.shape,
  colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
  elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(),
  border: BorderStroke? = FilterChipDefaults.filterChipBorder(enabled, selected),
  interactionSource: MutableInteractionSource? = null
) {
  val glass = LocalGlass.current
  // One source for the click, the hover tint and the press-in, so they move together.
  val source = interactionSource ?: androidx.compose.runtime.remember { MutableInteractionSource() }
  if (glass == null) {
    androidx.compose.material3.FilterChip(selected, onClick, label, modifier, enabled, leadingIcon, trailingIcon, shape, colors, elevation, border, interactionSource)
  } else {
    // Unselected: a recessed pill. Selected: the bright pill that sits on the glass track.
    androidx.compose.material3.FilterChip(
      selected = selected,
      onClick = onClick,
      label = label,
      modifier = modifier.pressScale(source),
      enabled = enabled,
      leadingIcon = leadingIcon,
      trailingIcon = trailingIcon,
      shape = CircleShape,
      colors = FilterChipDefaults.filterChipColors(
        containerColor = glass.inset,
        labelColor = glass.muted,
        iconColor = glass.muted,
        selectedContainerColor = glass.selected,
        selectedLabelColor = glass.text,
        selectedLeadingIconColor = glass.text,
        selectedTrailingIconColor = glass.text
      ),
      elevation = null,
      border = BorderStroke(0.8.dp, if (selected) glass.rimTop else glass.insetRim),
      interactionSource = source
    )
  }
}

/** Glass cards are rounder than phone cards, except for circles, which stay circles. */
@Composable
private fun glassCardShape(shape: Shape): Shape = if (shape == CircleShape) shape else MaterialTheme.shapes.large

/**
 * The fill for a glass card: a fully opaque colour asked for by a screen (a plain "surface" or one of
 * the old cream panels) becomes frosted glass; translucent or coloured fills are kept.
 */
@Composable
private fun glassFill(requested: Color, glass: GlassColors): Color =
  if (requested == Color.Unspecified || requested == Color.Transparent) glass.fill else requested

/**
 * Tabs. On the desktop they sit in a recessed glass pill track with a short ink mark under the
 * selected tab, instead of a full-width bar.
 */
@Composable
fun TabRow(
  selectedTabIndex: Int,
  modifier: Modifier = Modifier,
  containerColor: Color = androidx.compose.material3.TabRowDefaults.primaryContainerColor,
  contentColor: Color = androidx.compose.material3.TabRowDefaults.primaryContentColor,
  indicator: @Composable (tabPositions: List<androidx.compose.material3.TabPosition>) -> Unit = @Composable { tabPositions ->
    if (selectedTabIndex < tabPositions.size) {
      androidx.compose.material3.TabRowDefaults.SecondaryIndicator(
        androidx.compose.material3.TabRowDefaults.run { Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]) }
      )
    }
  },
  divider: @Composable () -> Unit = @Composable { androidx.compose.material3.HorizontalDivider() },
  tabs: @Composable () -> Unit
) {
  val glass = LocalGlass.current
  if (glass == null) {
    androidx.compose.material3.TabRow(selectedTabIndex, modifier, containerColor, contentColor, indicator, divider, tabs)
    return
  }
  var hoverX by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
  val density = androidx.compose.ui.platform.LocalDensity.current
  androidx.compose.foundation.layout.Box(
    modifier
      .glassInset(CircleShape, glass)
      .padding(horizontal = 6.dp)
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            hoverX = if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Exit) null
            else event.changes.firstOrNull()?.position?.x
          }
        }
      }
  ) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalRippleConfiguration provides null) {
    androidx.compose.material3.TabRow(
      selectedTabIndex = selectedTabIndex,
      containerColor = Color.Transparent,
      contentColor = glass.text,
      indicator = { tabPositions ->
        // A soft pill under the tab the pointer is over, the same tint as hover everywhere else.
        val x = hoverX?.let { with(density) { it.toDp() } }
        val hovered = x?.let { px -> tabPositions.firstOrNull { px >= it.left && px < it.right } }
        if (hovered != null) {
          androidx.compose.foundation.layout.Box(
            androidx.compose.material3.TabRowDefaults.run { Modifier.tabIndicatorOffset(hovered) }
              .fillMaxHeight()
              .padding(vertical = 5.dp, horizontal = 3.dp)
              .clip(CircleShape)
              .background((if (glass.dark) Color.White else Color.Black).copy(alpha = if (glass.dark) 0.10f else 0.075f))
          )
        }
        if (selectedTabIndex < tabPositions.size) {
          androidx.compose.foundation.layout.Box(
            androidx.compose.material3.TabRowDefaults.run { Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]) }
              .fillMaxHeight(),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter
          ) {
            androidx.compose.foundation.layout.Box(
              Modifier.padding(bottom = 6.dp).width(26.dp).height(3.dp).clip(CircleShape).background(glass.ink)
            )
          }
        }
      },
      divider = {},
      tabs = tabs
    )
    }
  }
}

/** A page's own background: kept on the phone, see-through on the desktop so the backdrop shows. */
@Composable
fun pageBackground(classic: Color): Color = if (LocalGlass.current != null) Color.Transparent else classic

/** A dialog's background: opaque frost on the desktop, since a see-through dialog is hard to read. */
@Composable
fun dialogBackground(classic: Color): Color = LocalGlass.current?.frost ?: classic

/** Small stat labels: as written on the phone, spaced capitals on the desktop. */
@Composable
fun statLabel(text: String): String = if (LocalGlass.current != null) text.uppercase() else text

/**
 * A clickable stat inside a row of stats. On the desktop each is its own recessed tile, so the hover
 * tint covers exactly the tile; on the phone it stays a plain column.
 */
@Composable
fun Modifier.statTile(raised: Boolean = false, onClick: () -> Unit): Modifier {
  val glass = LocalGlass.current
  val shape = androidx.compose.foundation.shape.RoundedCornerShape(if (raised) 22.dp else 16.dp)
  return if (glass != null) {
    // Raised tiles stand on the page; recessed ones sit inside a card.
    this.pressable(onClick) { if (raised) glass(shape, glass) else glassInset(shape, glass) }
      .padding(vertical = if (raised) 14.dp else 10.dp)
  } else {
    this.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)
  }
}

/** The thin line between stats on the phone; on the desktop the tiles are just spaced apart. */
@Composable
fun StatDivider() {
  if (LocalGlass.current != null) {
    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
  } else {
    androidx.compose.foundation.layout.Box(
      Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant)
    )
  }
}

/** A progress bar as one clean track: no gap before the remaining part and no dot at its end. */
@Composable
fun LinearProgressIndicator(
  progress: () -> Float,
  modifier: Modifier = Modifier,
  color: Color = androidx.compose.material3.ProgressIndicatorDefaults.linearColor,
  trackColor: Color = androidx.compose.material3.ProgressIndicatorDefaults.linearTrackColor,
  strokeCap: androidx.compose.ui.graphics.StrokeCap = androidx.compose.material3.ProgressIndicatorDefaults.LinearStrokeCap
) = androidx.compose.material3.LinearProgressIndicator(
  progress = progress,
  modifier = modifier,
  color = color,
  trackColor = trackColor,
  strokeCap = strokeCap,
  gapSize = 0.dp,
  drawStopIndicator = {}
)

/** The indeterminate bar, also without the gap between its parts. */
@Composable
fun LinearProgressIndicator(
  modifier: Modifier = Modifier,
  color: Color = androidx.compose.material3.ProgressIndicatorDefaults.linearColor,
  trackColor: Color = androidx.compose.material3.ProgressIndicatorDefaults.linearTrackColor
) = androidx.compose.material3.LinearProgressIndicator(
  modifier = modifier,
  color = color,
  trackColor = trackColor,
  gapSize = 0.dp
)

/**
 * Makes an element clickable and presses all of it in while held. [surface] is the element's own
 * look (its background, rim, clip), applied inside the press so the whole thing moves together.
 */
@Composable
fun Modifier.pressable(onClick: () -> Unit, surface: Modifier.() -> Modifier = { this }): Modifier {
  val source = androidx.compose.runtime.remember { MutableInteractionSource() }
  return this
    .pressScale(source)
    .surface()
    .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
}
