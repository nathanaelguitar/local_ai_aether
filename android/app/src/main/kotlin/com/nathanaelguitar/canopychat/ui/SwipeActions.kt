package com.nathanaelguitar.canopychat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One trailing-swipe action, mirroring a `Button` inside iOS's `.swipeActions`. */
data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val background: Color,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Trailing swipe-to-reveal actions, the Android stand-in for SwiftUI's
 * `.swipeActions(edge: .trailing, allowsFullSwipe: true)`.
 *
 * Actions are laid out right-to-left in the order given, matching how SwiftUI stacks them.
 * Dragging past [FULL_SWIPE_FRACTION] of the row width fires the destructive action outright,
 * which is what `allowsFullSwipe` does on iOS.
 */
@Composable
fun SwipeActionsRow(
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit
) {
    if (actions.isEmpty()) {
        Box(modifier) { content() }
        return
    }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val actionWidth = 76.dp
    val revealPx = with(density) { (actionWidth * actions.size).toPx() }
    val offsetX = remember { Animatable(0f) }
    var rowWidthPx by remember { mutableStateOf(0f) }

    fun settleClosed() = scope.launch { offsetX.animateTo(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .onSizeChanged { rowWidthPx = it.width.toFloat() }
    ) {
        // Action strip sits behind the row and is uncovered as the content slides left.
        // matchParentSize() takes its height from the content row; fillMaxHeight() alone
        // measures against unbounded constraints and leaves the buttons short.
        Box(modifier = Modifier.matchParentSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.End
        ) {
            actions.forEach { action ->
                Column(
                    modifier = Modifier
                        .width(actionWidth)
                        .fillMaxHeight()
                        .background(action.background)
                        .clickable {
                            settleClosed()
                            action.onClick()
                        }
                        .padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        action.icon,
                        contentDescription = action.label,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        action.label,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            // Only opens leftward; a little rubber-banding past the strip.
                            val next = (offsetX.value + delta).coerceIn(-revealPx * 1.6f, 0f)
                            offsetX.snapTo(next)
                        }
                    },
                    onDragStopped = {
                        val fullSwipe = rowWidthPx > 0f &&
                            offsetX.value <= -rowWidthPx * FULL_SWIPE_FRACTION
                        val destructive = actions.firstOrNull { it.isDestructive }
                        when {
                            fullSwipe && destructive != null -> {
                                offsetX.animateTo(0f)
                                destructive.onClick()
                            }
                            offsetX.value <= -revealPx / 2f -> offsetX.animateTo(-revealPx)
                            else -> offsetX.animateTo(0f)
                        }
                    }
                )
        ) {
            content()
        }
    }

    // Close the strip if the action list changes out from under it (row deleted, etc).
    LaunchedEffect(actions.size) { offsetX.animateTo(0f) }
}

private const val FULL_SWIPE_FRACTION = 0.55f
