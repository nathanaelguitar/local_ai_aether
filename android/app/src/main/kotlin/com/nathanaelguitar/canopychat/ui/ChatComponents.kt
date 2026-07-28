package com.nathanaelguitar.canopychat.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.nathanaelguitar.canopychat.R
import androidx.compose.ui.window.Dialog
import com.nathanaelguitar.canopychat.core.ChatAttachment
import kotlin.math.max
import kotlin.math.sin

/** Port of ChatEmptyState in iphone/AetherChat/ChatView.swift. */
@Composable
fun ChatEmptyState(personaName: String, isDark: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(OakColors.forestMedium.copy(alpha = if (isDark) 0.2f else 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_leaf_fill),
                contentDescription = null,
                tint = OakColors.forestMedium,
                modifier = Modifier.size(34.dp)
            )
        }
        Text(
            "Begin your conversation with $personaName",
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center,
            color = if (isDark) OakColors.oakCream else OakColors.warmBlack
        )
        Text(
            "Ask anything — answers run on your device.",
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = if (isDark) OakColors.warmGray400 else OakColors.warmGray500
        )
    }
}

/**
 * Port of TypingIndicator in iphone/AetherChat/ChatView.swift — a breathing amber
 * radial dot, shimmer gradient sweeping the status text, and five composing phrases
 * that rotate every six seconds.
 */
@Composable
fun TypingIndicator(message: String?, isDark: Boolean) {
    val transition = rememberInfiniteTransition(label = "typing")
    val breath by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    val sweep by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_270, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )
    // Rotating composing phrases, one every 6 s, matching iOS's TimelineView cadence.
    var phraseIndex by remember { mutableStateOf(0) }
    val showRotatingPhrase = message.isNullOrEmpty() || message == "Composing a response"
    LaunchedEffect(showRotatingPhrase) {
        if (!showRotatingPhrase) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(6_000)
            phraseIndex = (phraseIndex + 1).coerceAtMost(composingPhrases.lastIndex)
        }
    }

    val base = if (isDark) OakColors.warmGray400 else OakColors.warmGray500
    val highlight = if (isDark) OakColors.oakPale else OakColors.oakMedium
    val shimmerBrush = Brush.linearGradient(
        (0f to base),
        ((sweep - 0.25f).coerceIn(0f, 1f) to base),
        (sweep.coerceIn(0f, 1f) to highlight),
        ((sweep + 0.25f).coerceIn(0f, 1f) to base),
        (1f to base)
    )

    Row(modifier = Modifier.fillMaxWidth().padding(start = 2.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(if (isDark) OakColors.warmGray800 else Color.White)
                .border(
                    1.dp,
                    Brush.linearGradient(
                        if (isDark) {
                            listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f))
                        } else {
                            listOf(OakColors.oakPale.copy(alpha = 0.8f), OakColors.oakPale.copy(alpha = 0.3f))
                        }
                    ),
                    RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(18.dp)) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .scale(1f + 0.22f * breath)
                        .background(
                            Brush.radialGradient(
                                listOf(OakColors.amber.copy(alpha = 0.5f), Color.Transparent)
                            ),
                            CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .scale(1f + 0.14f * breath)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(OakColors.amber, OakColors.copper))
                        )
                )
            }
            Text(
                if (showRotatingPhrase) composingPhrases[phraseIndex] else message.orEmpty(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                style = androidx.compose.ui.text.TextStyle(brush = shimmerBrush)
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

private val composingPhrases = listOf(
    "Composing a response",
    "Gathering thoughts",
    "Choosing the right words",
    "Polishing the reply",
    "Still composing, thanks for waiting"
)

/** Port of StreamingBubble in iphone/AetherChat/ChatView.swift — live tokens + amber cursor. */
@Composable
fun StreamingBubble(text: String, isDark: Boolean, fontScale: Double) {
    val content = buildAnnotatedString {
        append(text)
        withStyle(SpanStyle(color = OakColors.amber, fontSize = (11 * fontScale).sp)) {
            append(" ●")
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Text(
            text = content,
            fontSize = (15 * fontScale).sp,
            color = if (isDark) OakColors.warmGray100 else OakColors.warmBlack,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

/**
 * Port of ModelLoadingOverlay in iphone/AetherChat/ChatView.swift. Covers the screen
 * while the model downloads and loads; the scrim absorbs taps so nothing behind it is
 * hit by accident.
 */
@Composable
fun ModelLoadingOverlay(message: String, isDark: Boolean) {
    val scrimInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background((if (isDark) Color.Black else OakColors.oakCream).copy(alpha = if (isDark) 0.58f else 0.46f))
            // Absorb taps so they don't reach the composer behind the scrim.
            .clickable(interactionSource = scrimInteraction, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(
                    if (isDark) OakColors.warmGray900.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.9f)
                )
                .border(1.dp, OakColors.oakPale.copy(alpha = 0.35f), RoundedCornerShape(30.dp))
                .padding(horizontal = 26.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            WoodlandWalkScene(isDark)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Rooting CanopyChat",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) OakColors.oakCream else OakColors.warmBlack
                )
                Text(
                    message,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = if (isDark) OakColors.warmGray200 else OakColors.warmGray600
                )
                Text(
                    "First launch can take a while while the model settles into local storage.",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = if (isDark) OakColors.warmGray400 else OakColors.warmGray500,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Port of WoodlandWalkScene in iphone/AetherChat/ChatView.swift — a little woodland
 * sprout endlessly walking toward a tree while the model loads.
 */
@Composable
private fun WoodlandWalkScene(isDark: Boolean) {
    val transition = rememberInfiniteTransition(label = "woodland")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cycle"
    )
    val legPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "legs"
    )
    val swayPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5_700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sway"
    )

    val walkPortion = 0.78f
    val startX = -104f
    val treeX = 88f
    val walking = cycle < walkPortion
    val walkProgress = (cycle / walkPortion).coerceAtMost(1f)
    val walkerX = startX + (treeX - 34 - startX) * walkProgress
    val celebrateT = if (walking) 0f else (cycle - walkPortion) / (1 - walkPortion)

    Box(modifier = Modifier.size(240.dp, 96.dp), contentAlignment = Alignment.Center) {
        // Ground line.
        Box(
            modifier = Modifier
                .offset(y = 40.dp)
                .size(236.dp, 3.dp)
                .clip(CircleShape)
                .background(OakColors.oakPale.copy(alpha = if (isDark) 0.35f else 0.6f))
        )

        // Swaying woodland tree. This intentionally has its own silhouette:
        // ic_canopy_tree is the app-logo oak, while iOS uses SF Symbols' broader
        // tree.fill with a visible branching trunk in this animation.
        WoodlandTree(
            modifier = Modifier
                .offset(x = treeX.dp, y = 18.dp)
                .size(50.dp)
                .graphicsLayer(
                    rotationZ = sin(swayPhase.toDouble()).toFloat() * 1.6f,
                    transformOrigin = TransformOrigin(0.5f, 1f)
                )
        )

        // Celebration leaf that floats up when the sprout arrives.
        if (!walking) {
            Icon(
                painterResource(R.drawable.ic_leaf_fill),
                contentDescription = null,
                tint = OakColors.forestMedium.copy(alpha = 0.85f),
                modifier = Modifier
                    .offset(
                        x = (treeX - 18 + sin(celebrateT * Math.PI * 2).toFloat() * 8).dp,
                        y = (10 - celebrateT * 34).dp
                    )
                    .size(11.dp)
                    .graphicsLayer(alpha = sin(celebrateT * Math.PI).toFloat().coerceIn(0f, 1f))
            )
        }

        // Walker.
        val bobPhase = legPhase * 7f / 9f
        val walkerY = 26f + if (walking) {
            -kotlin.math.abs(sin(bobPhase.toDouble())).toFloat() * 3
        } else {
            -kotlin.math.abs(sin(celebrateT * Math.PI * 2)).toFloat() * 8
        }
        val legSwing = if (walking) sin(legPhase.toDouble()).toFloat() * 24 else 0f
        Box(
            modifier = Modifier
                .offset(x = walkerX.dp, y = walkerY.dp)
                .size(width = 40.dp, height = 44.dp),
            contentAlignment = Alignment.Center
        ) {
            // Legs.
            Box(
                modifier = Modifier
                    .offset(x = (-4).dp, y = 15.dp)
                    .size(4.dp, 11.dp)
                    .graphicsLayer(
                        rotationZ = legSwing,
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    )
                    .clip(CircleShape)
                    .background(OakColors.oakMedium)
            )
            Box(
                modifier = Modifier
                    .offset(x = 4.dp, y = 15.dp)
                    .size(4.dp, 11.dp)
                    .graphicsLayer(
                        rotationZ = -legSwing,
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    )
                    .clip(CircleShape)
                    .background(OakColors.oakMedium)
            )
            // Body.
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(OakColors.amber, OakColors.copper)))
            )
            // Eye.
            Box(
                modifier = Modifier
                    .offset(x = 6.dp, y = (-4).dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
            // A plain woodland leaf, matching iOS's leaf.fill. Material's
            // EnergySavingsLeaf includes a badge/bolt and made the walker look
            // like a tiny status icon instead of a character.
            WoodlandLeaf(
                modifier = Modifier
                    .offset(x = 3.dp, y = (-17).dp)
                    .size(width = 15.dp, height = 11.dp)
                    .graphicsLayer(
                        rotationZ = -28f + if (walking) {
                            sin(bobPhase.toDouble()).toFloat() * 6
                        } else {
                            celebrateT * 20
                        }
                    )
            )
        }
    }
}

@Composable
private fun WoodlandTree(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val canopy = Brush.verticalGradient(
            colors = listOf(
                OakColors.forestMedium,
                OakColors.forestMedium.copy(alpha = 0.78f)
            ),
            startY = 0f,
            endY = size.height
        )

        // A broad, softly lobed crown like iOS's tree.fill.
        drawCircle(canopy, radius = size.width * 0.25f, center = androidx.compose.ui.geometry.Offset(size.width * 0.50f, size.height * 0.28f))
        drawCircle(canopy, radius = size.width * 0.22f, center = androidx.compose.ui.geometry.Offset(size.width * 0.30f, size.height * 0.40f))
        drawCircle(canopy, radius = size.width * 0.22f, center = androidx.compose.ui.geometry.Offset(size.width * 0.70f, size.height * 0.40f))
        drawCircle(canopy, radius = size.width * 0.20f, center = androidx.compose.ui.geometry.Offset(size.width * 0.48f, size.height * 0.47f))

        // The branching trunk is deliberately visible through the lower canopy.
        val trunk = Path().apply {
            moveTo(size.width * 0.42f, size.height * 0.94f)
            cubicTo(
                size.width * 0.45f,
                size.height * 0.77f,
                size.width * 0.45f,
                size.height * 0.67f,
                size.width * 0.42f,
                size.height * 0.55f
            )
            lineTo(size.width * 0.25f, size.height * 0.41f)
            lineTo(size.width * 0.31f, size.height * 0.37f)
            lineTo(size.width * 0.46f, size.height * 0.51f)
            lineTo(size.width * 0.48f, size.height * 0.32f)
            lineTo(size.width * 0.55f, size.height * 0.32f)
            lineTo(size.width * 0.54f, size.height * 0.53f)
            lineTo(size.width * 0.71f, size.height * 0.40f)
            lineTo(size.width * 0.77f, size.height * 0.45f)
            lineTo(size.width * 0.57f, size.height * 0.62f)
            cubicTo(
                size.width * 0.55f,
                size.height * 0.73f,
                size.width * 0.57f,
                size.height * 0.84f,
                size.width * 0.61f,
                size.height * 0.94f
            )
            close()
        }
        drawPath(trunk, OakColors.forestMedium.copy(alpha = 0.92f))
    }
}

@Composable
private fun WoodlandLeaf(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val leaf = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.72f)
            cubicTo(
                size.width * 0.22f,
                size.height * 0.05f,
                size.width * 0.72f,
                -size.height * 0.05f,
                size.width * 0.96f,
                size.height * 0.30f
            )
            cubicTo(
                size.width * 0.74f,
                size.height * 0.88f,
                size.width * 0.30f,
                size.height * 1.02f,
                size.width * 0.08f,
                size.height * 0.72f
            )
            close()
        }
        drawPath(leaf, OakColors.forestMedium)
        drawLine(
            color = OakColors.forestDark.copy(alpha = 0.72f),
            start = androidx.compose.ui.geometry.Offset(size.width * 0.05f, size.height * 0.88f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.34f),
            strokeWidth = size.height * 0.08f,
            cap = StrokeCap.Round
        )
    }
}

/** Port of AttachmentTray / AttachmentTrayItem in iphone/AetherChat/ChatView.swift. */
@Composable
fun AttachmentTray(
    attachments: List<ChatAttachment>,
    isDark: Boolean,
    onRemove: (ChatAttachment) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            Box {
                val bitmap = remember(attachment.id) {
                    if (!attachment.isImage) null else runCatching {
                        BitmapFactory.decodeByteArray(attachment.data, 0, attachment.data.size)?.asImageBitmap()
                    }.getOrNull()
                }

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(min = 56.dp)
                            .widthIn(max = 130.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                (if (isDark) OakColors.warmGray800 else OakColors.warmGray200).copy(alpha = 0.85f)
                            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = OakColors.oakMedium,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            attachment.displayName,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isDark) OakColors.warmGray100 else OakColors.oakDark
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(OakColors.warmBlack.copy(alpha = 0.72f))
                        .clickable(onClickLabel = "Remove attachment") { onRemove(attachment) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove attachment",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Port of PromptEditorSheet in iphone/AetherChat/ChatView.swift. Presented as a dialog
 * because Compose has no direct NavigationStack sheet equivalent.
 */
@Composable
fun PromptEditorSheet(
    initialText: String,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var draft by remember { mutableStateOf(initialText) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (isDark) OakColors.warmGray900 else OakColors.oakCream)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Edit Prompt",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) OakColors.oakCream else OakColors.warmBlack
            )

            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    color = if (isDark) OakColors.warmGray100 else OakColors.warmBlack
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDark) OakColors.warmGray800 else Color.White)
                    .padding(12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = oakSubtitle())
                }
                TextButton(onClick = { onSubmit(draft) }) {
                    Text("Save", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
