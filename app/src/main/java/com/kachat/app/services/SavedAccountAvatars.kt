package com.kachat.app.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.kachat.app.util.KaspaAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Each saved account's picture, kept on the phone (iOS 8cb4c88e `SavedAccountAvatars`): written
 * while the account is signed in (the dock's Profile picture, [com.kachat.app.ui.DockTabIcon]),
 * read by the sign-in screen's Saved Accounts and the account switcher, so neither looks another
 * address up. Filed by the account's public key (its address's payload, the same on mainnet and
 * testnet) under files/AccountAvatars, and removed with the account.
 */
object SavedAccountAvatars {
    private const val FOLDER = "AccountAvatars"
    /** Saved at this size: big enough for a 48 dp row on a 3x screen. */
    private const val PIXEL_SIDE = 144

    private val _revision = MutableStateFlow(0)
    /** Moves on every save or removal, so the rows showing a picture redraw. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val memory = HashMap<String, Bitmap>()
    private val missing = HashSet<String>()

    /** The account's public key, hex: its address's payload, one key on both networks. */
    fun publicKeyOf(address: String): String? = try {
        KaspaAddress.decode(address.trim()).second.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    /** The file name for [publicKey]: the first 8 bytes of SHA-256 of it, lowercased (as iOS). */
    internal fun fileKey(publicKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.lowercase().toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun folder(context: Context): File =
        File(context.applicationContext.filesDir, FOLDER).apply { mkdirs() }

    private fun file(context: Context, publicKey: String): File = File(folder(context), "${fileKey(publicKey)}.png")

    /** Already in memory, or known to be missing: no disk read needed. */
    @Synchronized
    fun cached(publicKey: String): Bitmap? = memory[fileKey(publicKey)]

    /** [publicKey]'s picture, read from disk the first time (call off the main thread). */
    fun image(context: Context, publicKey: String): Bitmap? {
        val key = fileKey(publicKey)
        synchronized(this) {
            memory[key]?.let { return it }
            if (key in missing) return null
        }
        val decoded = try {
            file(context, publicKey).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        } catch (e: Exception) {
            null
        }
        synchronized(this) {
            if (decoded == null) missing += key else memory[key] = decoded
        }
        return decoded
    }

    /** The signed-in account's picture changed (or was first worked out). Call off the main thread. */
    fun save(context: Context, source: Bitmap, publicKey: String) {
        val square = centerCropped(source, PIXEL_SIDE)
        val data = ByteArrayOutputStream().use { out ->
            if (!square.compress(Bitmap.CompressFormat.PNG, 100, out)) return
            out.toByteArray()
        }
        val target = file(context, publicKey)
        val key = fileKey(publicKey)
        synchronized(this) {
            val old = try { target.takeIf { it.isFile }?.readBytes() } catch (e: Exception) { null }
            if (old != null && old.contentEquals(data)) {
                if (memory[key] == null) memory[key] = square
                missing -= key
                return
            }
            try {
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeBytes(data)
                if (!temp.renameTo(target)) {
                    target.writeBytes(data)
                    temp.delete()
                }
            } catch (e: Exception) {
                return
            }
            memory[key] = square
            missing -= key
        }
        _revision.value += 1
    }

    /** The account has no picture any more, or the account was deleted. */
    fun remove(context: Context, publicKey: String) {
        val key = fileKey(publicKey)
        val target = file(context, publicKey)
        synchronized(this) {
            memory.remove(key)
            if (!target.exists()) return
            target.delete()
            missing += key
        }
        _revision.value += 1
    }

    /** By address: nothing when it doesn't decode. */
    fun removeFor(context: Context, address: String) {
        publicKeyOf(address)?.let { remove(context, it) }
    }

    /** Scaled to fill a [side]-pixel square, centred (iOS draws it aspect-fill the same way). */
    private fun centerCropped(source: Bitmap, side: Int): Bitmap {
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val w = source.width.coerceAtLeast(1).toFloat()
        val h = source.height.coerceAtLeast(1).toFloat()
        val scale = maxOf(side / w, side / h)
        val dw = w * scale
        val dh = h * scale
        val dst = RectF((side - dw) / 2f, (side - dh) / 2f, (side + dw) / 2f, (side + dh) / 2f)
        val safe = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        Canvas(out).drawBitmap(safe, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Tests only. */
    @Synchronized
    internal fun resetForTest() {
        memory.clear()
        missing.clear()
        _revision.value = 0
    }
}
