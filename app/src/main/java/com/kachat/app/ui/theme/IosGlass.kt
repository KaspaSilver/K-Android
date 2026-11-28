package com.kachat.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SwiftUI's `.shadow(color:radius:x:y:)` under a rounded card: [color] blurred by [radius] and
 * dropped by [y], drawn behind the card with the platform's shadow layer. Its Paint is made once
 * per size, not on every draw (audit 2026-11-27 Smoothness S9: about 44 glass cards each
 * allocated one per frame while a screen scrolled). Hardware rendering draws a shadow layer on
 * shapes only from Android 9; on 8 the card gets an elevation shadow of about the same reach
 * instead of none.
 */
fun Modifier.iosShadow(cornerRadius: Dp, color: Color, radius: Dp, y: Dp): Modifier =
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
        this.shadow(elevation = radius / 2, shape = RoundedCornerShape(cornerRadius), clip = false)
    } else {
        this.drawWithCache {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.TRANSPARENT
                setShadowLayer(radius.toPx(), 0f, y.toPx(), color.toArgb())
            }
            val r = cornerRadius.toPx()
            onDrawBehind {
                drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint) }
            }
        }
    }

/**
 * The app's frosted card as iOS draws it with `glassBackground` (the action sheet rows and tiles,
 * Cold Storage's and the chats' cards): `.regularMaterial` in a [cornerRadius] rounded rectangle,
 * a 0.8 white hairline at 18%, and a soft shadow (black at 12%, radius 10, 5 down - iOS
 * `kachatGlass` uses 10%, 8 and 4). The material is [AppColors.surface]: a blur of what lies
 * beneath cannot be drawn on older Android, so each surface carries a solid stand-in for it
 * (see [LightPlainSheetAppColors]).
 */
@Composable
fun Modifier.iosGlass(
    cornerRadius: Dp,
    shadowAlpha: Float = 0.12f,
    shadowRadius: Dp = 10.dp,
    shadowY: Dp = 5.dp,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .iosShadow(cornerRadius, Color.Black.copy(alpha = shadowAlpha), shadowRadius, shadowY)
        .clip(shape)
        .background(LocalAppColors.current.surface)
        .border(0.8.dp, Color.White.copy(alpha = 0.18f), shape)
}
