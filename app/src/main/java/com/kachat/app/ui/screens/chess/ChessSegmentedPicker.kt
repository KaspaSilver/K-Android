package com.kachat.app.ui.screens.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.LocalAppColors

/**
 * iOS 26's segmented Picker: a 32-high capsule track (systemFill grey) with the chosen segment on
 * a raised capsule thumb, the labels 14pt. KaPlay drew it with KaPost's `KaPostsSegmentedPicker`;
 * the chess screens keep their own copy, so they don't depend on KaPost's files.
 */
@Composable
internal fun ChessSegmentedPicker(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val track = Color(0xFF767680).copy(alpha = if (colors.isDark) 0.24f else 0.12f)
    val thumb = if (colors.isDark) Color(0xFF5A5A5E) else Color.White
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .padding(2.dp),
    ) {
        titles.forEachIndexed { index, title ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(if (active) thumb else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
