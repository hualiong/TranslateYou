/*
 * Copyright (c) 2025 You Apps
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.bnyro.translate.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Material 3 expressive style loading indicator: a twelve lobed shape that
 * morphs between a rounded polygon and a star while slowly rotating.
 */
@Composable
fun MorphingLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val transition = rememberInfiniteTransition(label = "loading indicator")

    val morph by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shape"
    )

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing)
        ),
        label = "rotation"
    )

    MorphingShape(
        modifier = modifier,
        morph = morph,
        rotation = rotation,
        color = MaterialTheme.colorScheme.primary,
        size = size
    )
}

@Composable
private fun MorphingShape(
    modifier: Modifier,
    morph: Float,
    rotation: Float,
    color: Color,
    size: Dp
) {
    Canvas(modifier = modifier.size(size)) {
        val radius = this.size.minDimension / 2

        // a positive bulge pinches the outline into a star, a negative one rounds it
        // out into a flower; at zero it is a plain circle
        val bulge = 0.13f * (morph * 2f - 1f)
        val vertices = 12
        val samples = vertices * 8

        val path = Path()
        for (i in 0..samples) {
            val angle = 2 * PI * i / samples
            val lobe = cos(vertices * (angle - rotation * PI / 180f)).toFloat()
            val r = radius * (1f + bulge * lobe)
            val point = Offset(
                x = center.x + r * cos(angle).toFloat(),
                y = center.y + r * sin(angle).toFloat()
            )
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()

        drawPath(path = path, color = color)
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingIndicatorPreview() {
    Row(modifier = Modifier.padding(16.dp)) {
        listOf(0f, 0.5f, 1f).forEach {
            MorphingShape(
                modifier = Modifier.padding(8.dp),
                morph = it,
                rotation = 0f,
                color = MaterialTheme.colorScheme.primary,
                size = 44.dp
            )
        }
    }
}
