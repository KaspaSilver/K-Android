package com.kachat.app.ui.screens.chess

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp

/**
 * The Chess section's icon: a rook and a knight, side by side (iOS `ChessTabIcon`, Views/Shared,
 * back from KaPlay in 148a5331). The Material set has no chess pieces, so the two glyphs are
 * drawn once into a bitmap - black on transparent, so an Icon's tint colours it like any other
 * symbol - and scaled to whatever size each place asks for.
 */
private val chessIconBitmap by lazy {
    val side = 96
    val width = (side * 1.55f).toInt()
    val bitmap = Bitmap.createBitmap(width, side, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = side * 1.08f
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    // U+FE0E asks for the text glyphs, never an emoji rendering.
    val text = "♜︎♞︎"
    val metrics = paint.fontMetrics
    val baseline = side / 2f - (metrics.ascent + metrics.descent) / 2f
    Canvas(bitmap).drawText(text, width / 2f, baseline, paint)
    bitmap.asImageBitmap()
}

/** The rook and knight as a tintable painter (iOS `ChessTabIcon.image(side:)`). */
@Composable
fun chessTabIconPainter(): Painter = remember { BitmapPainter(chessIconBitmap) }

/** The box the icon is drawn in, [height] tall at its natural width (iOS draws it at the full
 *  height with the pieces filling 1.55 of it). */
fun chessTabIconModifier(height: Dp): Modifier =
    Modifier.size(width = height * (chessIconBitmap.width.toFloat() / chessIconBitmap.height), height = height)
