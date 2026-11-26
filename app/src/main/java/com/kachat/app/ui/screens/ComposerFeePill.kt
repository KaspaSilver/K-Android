package com.kachat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit

/**
 * The fee pill above a chat composer (iOS `feeBubble`, the same in 1:1, group and public chats):
 * "fee: 0.00001234 KAS" underlined, which opens the fee editor; while a re-price waits for its
 * pause (see [com.kachat.app.util.TypingFeeGate]), "fee: -------- KAS" under a light sweeping
 * across it, and not tappable; "fee: -- KAS" when there is no fee to show.
 *
 * Only a text message's fee can be adjusted (iOS 83806b3d `feeIsAdjustable`): a voice note or
 * photo prices its own parts, so with [adjustable] false the pill shows that fee, not underlined,
 * and can't be tapped.
 */
@Composable
fun ComposerFeePill(
    feeSompi: Long?,
    estimating: Boolean,
    onTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
    adjustable: Boolean = true,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(14.dp)
    val text = when {
        estimating -> stringResource(R.string.composer_fee_estimating)
        feeSompi != null -> stringResource(R.string.composer_fee_amount, "%.8f".format(java.util.Locale.US, feeSompi / 100_000_000.0))
        else -> stringResource(R.string.composer_fee_unknown)
    }
    Text(
        KaspaUnit.label(text),
        color = colors.textSecondary,
        fontSize = 11.sp,
        textDecoration = if (adjustable && !estimating && feeSompi != null) TextDecoration.Underline else null,
        modifier = modifier
            .sendKaspaGlass(14.dp)
            .clip(shape)
            .then(if (estimating) Modifier.feeShimmer() else Modifier)
            .clickable(
                enabled = adjustable && !estimating && feeSompi != null,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { feeSompi?.let(onTap) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * iOS's `FeeShimmerOverlay`: a band of light (clear - 22% white - clear, corner to corner) tilted
 * 20 degrees, sweeping left to right across the pill every 1.2 s. Drawn at about 30 fps rather
 * than every frame, and not at all inside a hidden Kaspa Hub section; each step waits for a frame,
 * so it stops with the frame clock in the background (audit 2026-11-25 R4).
 */
@Composable
private fun Modifier.feeShimmer(): Modifier {
    val phase = remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
    val hidden = LocalHubSectionHidden.current
    androidx.compose.runtime.LaunchedEffect(hidden) {
        if (hidden) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) {
            kotlinx.coroutines.delay(FEE_SHIMMER_FRAME_MS)
            androidx.compose.runtime.withFrameMillis { now ->
                phase.floatValue = feeShimmerPhase(now - start)
            }
        }
    }
    return drawWithContent {
        drawContent()
        val brush = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        translate(left = phase.floatValue * size.width * 1.5f) {
            rotate(degrees = 20f) {
                drawRect(brush = brush)
            }
        }
    }
}

/** About 30 fps for [feeShimmer]. */
internal const val FEE_SHIMMER_FRAME_MS = 33L
private const val FEE_SHIMMER_PERIOD_MS = 1_200L

/** The shimmer band's place, -1 (left) to 1 (right), [elapsedMs] into the sweep; restarts each 1.2 s. */
internal fun feeShimmerPhase(elapsedMs: Long): Float =
    -1f + 2f * (Math.floorMod(elapsedMs, FEE_SHIMMER_PERIOD_MS).toFloat() / FEE_SHIMMER_PERIOD_MS)
