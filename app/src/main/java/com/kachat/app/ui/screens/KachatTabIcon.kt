package com.kachat.app.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import com.kachat.app.ui.theme.KaspaTeal

/**
 * The ".kachat" wordmark as a tintable painter (iOS 148a5331 `KachatTabIcon`, in
 * KachatMarketView.swift) - the .kachat tab's dock item and Kaspa Hub tile, the .kachat hero and
 * the names guide draw it. The word is drawn at whatever size it is given (crisp from the 24 dp
 * dock item to the 56 dp hero), [KACHAT_WORDMARK_SIDE] units tall with its natural width, black,
 * so an Icon's tint colours it like any other symbol. From KaPay (KaChat-Wallet-Android
 * `ChessTabIcon.kt`).
 *
 * iOS draws it in SF Pro Rounded Heavy; Apple's fonts are licensed for Apple platforms only, so it
 * cannot ship here, and the heaviest system face (sans-serif-black) stands in.
 */
private const val KACHAT_WORDMARK_SIDE = 96f

private const val KACHAT_WORDMARK_TEXT = ".kachat"

private fun kachatWordmarkPaint(side: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.BLACK
    textSize = side * 0.62f
    // iOS draws it heavy (UIFont .heavy, rounded design); black is the heaviest system weight.
    typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    textAlign = Paint.Align.CENTER
}

/** The wordmark's width at [KACHAT_WORDMARK_SIDE] tall: the word's, a little air, never under square. */
private val kachatWordmarkWidth by lazy {
    maxOf(kachatWordmarkPaint(KACHAT_WORDMARK_SIDE).measureText(KACHAT_WORDMARK_TEXT) + 8f, KACHAT_WORDMARK_SIDE)
}

/** The wordmark painter; [rememberKachatWordmarkPainter] keeps one per composition. */
class KachatWordmarkPainter : Painter() {
    private var filter: ColorFilter? = null
    private var alpha = 1f

    override val intrinsicSize: Size get() = Size(kachatWordmarkWidth, KACHAT_WORDMARK_SIDE)

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        filter = colorFilter
        return true
    }

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun DrawScope.onDraw() {
        // drawn into the box the painter was given, keeping the word's proportions
        val scale = minOf(size.width / kachatWordmarkWidth, size.height / KACHAT_WORDMARK_SIDE)
        val side = KACHAT_WORDMARK_SIDE * scale
        val paint = kachatWordmarkPaint(side).apply {
            colorFilter = filter?.asAndroidColorFilter()
            alpha = (this@KachatWordmarkPainter.alpha * 255).toInt().coerceIn(0, 255)
        }
        val metrics = paint.fontMetrics
        val baseline = size.height / 2f - (metrics.ascent + metrics.descent) / 2f
        drawIntoCanvas { it.nativeCanvas.drawText(KACHAT_WORDMARK_TEXT, size.width / 2f, baseline, paint) }
    }
}

/** The .kachat tab's icon painter (the Usernames tab's wordmark). */
@Composable
fun rememberKachatWordmarkPainter(): Painter = remember { KachatWordmarkPainter() }

/**
 * The box the wordmark is drawn in, [height] tall: the word is wider than it is tall, and iOS
 * draws it at the full height with its natural width (KachatTabIcon `image(side:)`); a square
 * would shrink it to a sliver.
 */
fun kachatWordmarkModifier(height: Dp): Modifier =
    Modifier.size(width = height * (kachatWordmarkWidth / KACHAT_WORDMARK_SIDE), height = height)

/**
 * The .kachat wordmark [height] tall in [tint] (iOS `KachatTabIcon.view(side:)`): the .kachat
 * hero and the names guide's first step draw it at 56.
 */
@Composable
fun KachatWordmark(height: Dp, modifier: Modifier = Modifier, tint: Color = KaspaTeal) {
    androidx.compose.material3.Icon(
        painter = rememberKachatWordmarkPainter(),
        contentDescription = ".kachat",
        tint = tint,
        modifier = modifier.then(kachatWordmarkModifier(height)),
    )
}
