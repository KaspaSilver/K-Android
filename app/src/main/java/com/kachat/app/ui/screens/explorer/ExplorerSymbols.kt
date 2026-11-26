package com.kachat.app.ui.screens.explorer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * SF Symbols the Explorer draws that Material has no icon for, as stroked or filled vectors so
 * they tint and size like any other icon.
 */
object ExplorerSymbols {
    private fun stroked(name: String, width: Float = 1.6f, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        ).build()

    /** "cube.transparent": a cube with every edge drawn, the Explorer's tab icon (iOS `AppTab.explorer`). */
    val CubeTransparent: ImageVector by lazy {
        stroked("CubeTransparent") {
            // the outline
            moveTo(12f, 2.5f); lineTo(20.5f, 7.2f); lineTo(20.5f, 16.8f); lineTo(12f, 21.5f)
            lineTo(3.5f, 16.8f); lineTo(3.5f, 7.2f); close()
            // the front corner's three edges
            moveTo(12f, 12f); lineTo(3.5f, 7.2f)
            moveTo(12f, 12f); lineTo(20.5f, 7.2f)
            moveTo(12f, 12f); lineTo(12f, 21.5f)
            // and the back corner's, seen through it
            moveTo(12f, 12f); lineTo(12f, 2.5f)
            moveTo(12f, 12f); lineTo(3.5f, 16.8f)
            moveTo(12f, 12f); lineTo(20.5f, 16.8f)
        }
    }

    /** "cube.transparent.fill": a solid cube with its edges cut through (block rows, a block's page). */
    val CubeFill: ImageVector by lazy {
        ImageVector.Builder(
            name = "CubeFill",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black)) {
            // three faces, a hair apart, so the edges read
            moveTo(12f, 2.6f); lineTo(20.2f, 7.1f); lineTo(12f, 11.6f); lineTo(3.8f, 7.1f); close()
            moveTo(3.4f, 7.9f); lineTo(11.5f, 12.4f); lineTo(11.5f, 21.4f); lineTo(3.4f, 16.9f); close()
            moveTo(20.6f, 7.9f); lineTo(12.5f, 12.4f); lineTo(12.5f, 21.4f); lineTo(20.6f, 16.9f); close()
        }.build()
    }

    /** "cube": an outlined cube (a block's parents and children). */
    val Cube: ImageVector by lazy {
        stroked("Cube") {
            moveTo(12f, 2.5f); lineTo(20.5f, 7.2f); lineTo(20.5f, 16.8f); lineTo(12f, 21.5f)
            lineTo(3.5f, 16.8f); lineTo(3.5f, 7.2f); close()
            moveTo(3.5f, 7.2f); lineTo(12f, 12f); lineTo(20.5f, 7.2f)
            moveTo(12f, 12f); lineTo(12f, 21.5f)
        }
    }
}
