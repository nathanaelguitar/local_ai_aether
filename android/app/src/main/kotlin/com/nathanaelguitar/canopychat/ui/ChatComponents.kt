package com.nathanaelguitar.canopychat.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("🌿", fontSize = 60.sp)
        Text(
            "Begin your conversation with $personaName",
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            color = if (isDark) OakColors.warmGray500 else OakColors.warmGray600
        )
    }
}

/**
 * Port of TypingIndicator in iphone/AetherChat/ChatView.swift — three dots that lift
 * out of phase, with the current generation status above them.
 */
@Composable
fun TypingIndicator(message: String?, isDark: Boolean) {
    val transition = rememberInfiniteTransition(label = "typing")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_208, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (isDark) OakColors.warmGray800 else Color.White)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (!message.isNullOrEmpty()) {
                Text(
                    message,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDark) OakColors.warmGray200 else OakColors.warmGray600
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(16.dp)
            ) {
                repeat(3) { index ->
                    val phase = time - index * 0.62f
                    val lift = max(0.0, sin(phase.toDouble()))
                    Box(
                        modifier = Modifier
                            .padding(bottom = (lift * 7).dp)
                            .size(8.dp)
                            .scale((1 + lift * 0.22).toFloat())
                            .clip(CircleShape)
                            .background(
                                (if (isDark) OakColors.warmGray400 else OakColors.warmGray500)
                                    .copy(alpha = (0.62 + lift * 0.38).toFloat())
                            )
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * Port of ModelLoadingOverlay in iphone/AetherChat/ChatView.swift. Covers the screen
 * while the model downloads and loads; the scrim absorbs taps so nothing behind it is
 * hit by accident.
 */
@Composable
fun ModelLoadingOverlay(message: String, isDark: Boolean) {
    val transition = rememberInfiniteTransition(label = "loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

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
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(116.dp)) {
                    val stroke = 14.dp.toPx()
                    drawArc(
                        color = OakColors.oakPale.copy(alpha = 0.28f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = stroke)
                    )
                    rotate(rotation) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(
                                    OakColors.oakMedium,
                                    OakColors.copper,
                                    OakColors.amber,
                                    OakColors.oakMedium
                                )
                            ),
                            // iOS trims the ring from 0.08 to 0.72 of a full turn.
                            startAngle = 0.08f * 360f,
                            sweepAngle = (0.72f - 0.08f) * 360f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Icon(
                    painterResource(R.drawable.ic_canopy_tree),
                    contentDescription = null,
                    tint = OakColors.oakMedium,
                    modifier = Modifier.size(40.dp).scale(pulse)
                )
            }

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
                    Text("Cancel", color = OakColors.warmGray500)
                }
                TextButton(onClick = { onSubmit(draft) }) {
                    Text("Save", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
