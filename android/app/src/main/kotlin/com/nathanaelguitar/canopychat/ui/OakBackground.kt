package com.nathanaelguitar.canopychat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.sin

// Compose port of OakBackground / OakInteriorCanvas from iphone/AetherChat/OakBackground.swift.
@Composable
fun OakBackground(isDark: Boolean, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val baseColors = if (isDark) {
                listOf(Color(0xFF201A14), Color(0xFF2C2318), Color(0xFF3A2E1F))
            } else {
                listOf(Color(0xFFF5EDE0), Color(0xFFEADDC6), Color(0xFFD8C5A4))
            }
            val grain = if (isDark) Color(0xFFD4B896) else Color(0xFF3D2914)
            val grainAlpha = if (isDark) 0.035f else 0.045f
            val glow = Color(0xFFD4A017)
            val glowAlpha = if (isDark) 0.045f else 0.07f

            drawRect(brush = Brush.linearGradient(baseColors, start = Offset.Zero, end = Offset(w * 0.15f, h)))

            // Vertical plank seams
            for (i in 1..3) {
                val x = w * i / 4f + sin(i * 3.7) .toFloat() * 8f
                val seam = Path().apply {
                    moveTo(x, 0f)
                    cubicTo(x - 4f, h * 0.35f, x + 6f, h * 0.7f, x + 3f, h)
                }
                drawPath(seam, grain.copy(alpha = if (isDark) 0.05f else 0.06f), style = Stroke(width = 1.2f))
            }

            // Long vertical grain streaks
            for (i in 0 until 14) {
                val t = i / 13f
                val x = w * (0.04f + t * 0.92f) + sin(i * 12.9898).toFloat() * 10f
                val sway = 6f + 8f * abs(sin(i * 4.31)).toFloat()
                val streak = Path().apply {
                    moveTo(x, -10f)
                    cubicTo(x + sway, h * 0.33f, x - sway, h * 0.66f, x + sway * 0.4f, h + 10f)
                }
                val width = 0.8f + 0.9f * abs(sin(i * 7.7)).toFloat()
                drawPath(streak, grain.copy(alpha = grainAlpha), style = Stroke(width = width))
            }

            // Cathedral grain arcs
            val cathedrals = listOf(
                Triple(0.16f, 0.24f, 1.0f),
                Triple(0.62f, 0.52f, 1.3f),
                Triple(0.36f, 0.80f, 0.9f)
            )
            for ((fx, fy, scale) in cathedrals) {
                val cx = w * fx
                val cy = h * fy
                for (ring in 0 until 4) {
                    val rw = (26f + ring * 20f) * scale
                    val rh = rw * 2.6f
                    val arch = Path().apply {
                        moveTo(cx - rw, cy + rh)
                        cubicTo(cx - rw, cy - rh * 0.7f, cx + rw, cy - rh * 0.7f, cx + rw, cy + rh)
                    }
                    drawPath(
                        arch,
                        grain.copy(alpha = grainAlpha * (1f - ring * 0.18f)),
                        style = Stroke(width = 1.1f)
                    )
                }
            }

            // Warm glow, top-left and near the floor
            drawOval(
                color = glow.copy(alpha = glowAlpha),
                topLeft = Offset(-w * 0.3f, -h * 0.25f),
                size = androidx.compose.ui.geometry.Size(w * 1.1f, h * 0.75f)
            )
            drawOval(
                color = glow.copy(alpha = glowAlpha * 0.8f),
                topLeft = Offset(w * 0.35f, h * 0.72f),
                size = androidx.compose.ui.geometry.Size(w * 0.95f, h * 0.55f)
            )

            // Vignette
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (isDark) 0.16f else 0.05f)),
                    center = Offset(w / 2f, h / 2f),
                    radius = maxOf(w, h) * 0.85f
                )
            )
        }
        content()
    }
}
