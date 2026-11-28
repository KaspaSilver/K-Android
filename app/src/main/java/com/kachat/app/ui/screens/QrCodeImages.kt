package com.kachat.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QR codes at one pixel per module, made off the main thread (audit 2026-11-27 Smoothness S2).
 *
 * The shared helper used to encode at the drawn size times a 512 dp default - 1,500-2,000 px, a
 * 9-16 MB pixel array and bitmap - inside `remember {}` during composition, so every chat composer
 * of a 0-balance user (the funding card) and every Receive or address sheet opened with a hitch.
 * A module image is a few KB; [QrModulePainter] draws it scaled up without filtering, which is
 * what ZXing's own scaling did: a whole number of pixels per module, centred.
 */
object QrModules {
    /** Made codes by content, margin and mode - a few KB each (iOS 01f71184 swapQRCache). */
    private val cache = android.util.LruCache<String, ImageBitmap>(16)

    fun cached(key: String): ImageBitmap? = cache.get(key)

    fun key(content: String, margin: Int, byteMode: Boolean): String =
        "${if (byteMode) 'b' else 't'}|$margin|$content"

    /**
     * [content] as a QR, one pixel per module with [margin] modules of quiet zone. [byteMode]:
     * the content is raw bytes carried in ISO-8859-1 (a KSPT frame, KasSigner's raw-byte QR).
     * Null for no content or content too long for a QR.
     */
    fun encode(content: String, margin: Int, byteMode: Boolean = false): ImageBitmap? {
        if (content.isEmpty()) return null
        val key = key(content, margin, byteMode)
        cache.get(key)?.let { return it }
        val matrix = try {
            com.google.zxing.qrcode.QRCodeWriter().encode(
                content,
                com.google.zxing.BarcodeFormat.QR_CODE,
                0,
                0,
                buildMap {
                    put(com.google.zxing.EncodeHintType.MARGIN, margin)
                    if (byteMode) put(com.google.zxing.EncodeHintType.CHARACTER_SET, "ISO-8859-1")
                },
            )
        } catch (e: Exception) {
            return null
        }
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }
}

/** The largest whole number of pixels per module that fits [modules] modules in [sidePx]. */
internal fun qrPixelsPerModule(modules: Int, sidePx: Float): Int =
    if (modules <= 0) 1 else kotlin.math.floor(sidePx / modules).toInt().coerceAtLeast(1)

/**
 * Draws a module-sized QR at the largest whole-number scale that fits, centred, unfiltered: crisp
 * module edges, as the full-size encode produced. [image] null draws nothing (not made yet, or no
 * content). [intrinsic]: the size it reports for a caller that doesn't size the Image itself.
 */
class QrModulePainter(
    private val image: ImageBitmap?,
    private val intrinsic: Size = Size.Unspecified,
) : Painter() {
    override val intrinsicSize: Size get() = intrinsic

    override fun DrawScope.onDraw() {
        val image = image ?: return
        val modules = maxOf(image.width, image.height)
        val scale = qrPixelsPerModule(modules, minOf(size.width, size.height))
        val drawn = modules * scale
        val left = ((size.width - drawn) / 2f).toInt()
        val top = ((size.height - drawn) / 2f).toInt()
        drawImage(
            image = image,
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(drawn, drawn),
            filterQuality = FilterQuality.None,
        )
    }
}

@Composable
private fun rememberQrModulePainter(content: String, size: Int, padding: Int, byteMode: Boolean): Painter {
    val sizePx = with(LocalDensity.current) { size.dp.roundToPx() }.toFloat()
    val key = QrModules.key(content, padding, byteMode)
    // A code already made shows in the first frame; a new one a frame or two later.
    val image by produceState(initialValue = QrModules.cached(key), key) {
        if (value != null || content.isEmpty()) return@produceState
        value = withContext(Dispatchers.Default) { QrModules.encode(content, padding, byteMode) }
    }
    return remember(image, sizePx) { QrModulePainter(image, Size(sizePx, sizePx)) }
}

/** [content] as a QR code, [size] dp for a caller that doesn't size the Image itself. */
@Composable
fun rememberQrBitmapPainter(
    content: String,
    size: Int = 512,
    padding: Int = 0
): Painter = rememberQrModulePainter(content, size, padding, byteMode = false)

/**
 * Byte-mode QR encoding for raw binary payloads (e.g. a KSPT transaction frame) — ISO-8859-1 maps
 * every byte 1:1 to a char and back, so wrapping [bytes] this way and forcing ZXing's
 * `CHARACTER_SET` hint round-trips the exact bytes through its String-based encoder in byte mode,
 * matching KasSigner's own raw-byte QR encoding (`qrcode::QrCode::new(&[u8])`) on the other end.
 */
@Composable
fun rememberQrBitmapPainter(
    bytes: ByteArray,
    size: Int = 512,
    padding: Int = 0
): Painter {
    val content = remember(bytes) { String(bytes, Charsets.ISO_8859_1) }
    return rememberQrModulePainter(content, size, padding, byteMode = true)
}
