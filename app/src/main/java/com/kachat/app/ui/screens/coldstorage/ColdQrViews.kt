package com.kachat.app.ui.screens.coldstorage

import com.kachat.app.ui.screens.*

import com.kachat.app.R
import com.kachat.app.util.tr
import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.QrFrameChunker
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// KasSigner's animated QR exchange (iOS 148a5331 `Views/Shared/MultiFrameQRViews.swift`,
// `ByteModeQRCode`): the unsigned transaction shown frame by frame, and the signed one scanned
// back. From KaPay; KaChat's own scanner (QrScannerScreen.kt) keeps its camera pieces private, so
// the multi-frame sheet carries its own copy of them here.

/**
 * Cycles through [frames] at a fixed interval — the display half of KasSigner's animated multi-
 * frame QR protocol (see [QrFrameChunker]). A single-frame list just renders a static code with no
 * play/pause controls or frame counter. Each frame is a byte-mode QR ([rememberQrBitmapPainter]'s
 * ByteArray form), as KasSigner reads it.
 */
@Composable
internal fun AnimatedQrDisplay(frames: List<ByteArray>, modifier: Modifier = Modifier, frameDelayMs: Long = 2500L) {
    var frameIndex by remember(frames) { mutableStateOf(0) }
    // Matches KasSee's own 2.5s auto-advance: a scanning camera needs real time to lock onto and
    // decode each frame.
    var isPlaying by remember(frames) { mutableStateOf(true) }

    LaunchedEffect(frames, isPlaying) {
        if (frames.size <= 1 || !isPlaying) return@LaunchedEffect
        while (true) {
            delay(frameDelayMs)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    // Every frame encoded once, off the main thread, at one pixel per module (a few KB each), and
    // drawn scaled up with no filtering - re-encoding a full-size bitmap on the main thread every
    // 2.5 s made the sheet hitch.
    val frameImages by androidx.compose.runtime.produceState<List<androidx.compose.ui.graphics.ImageBitmap>?>(null, frames) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            frames.map { encodeQrModules(it) }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(2.dp, KaspaTeal, RoundedCornerShape(20.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val image = frameImages?.getOrNull(frameIndex.coerceIn(frames.indices))
            if (image != null) {
                val painter = remember(image) { QrModulePainter(image) }
                Image(painter = painter, contentDescription = stringResource(R.string.qr_code_2), modifier = Modifier.fillMaxSize())
            }
        }
        if (frames.size > 1) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                frames.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            // fixed, not themed: this always sits on a white card
                            .background(if (i == frameIndex) KaspaTeal else Color(0xFFD0D0D5))
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { frameIndex = (frameIndex - 1 + frames.size) % frames.size }) {
                    // Translated (iOS 518fdc8c: the multi-frame QR was among the texts with no key).
                    Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous), tint = KaspaTeal)
                }
                IconButton(onClick = { isPlaying = !isPlaying }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) stringResource(R.string.kpui_pause) else stringResource(R.string.kpui_play),
                        tint = KaspaTeal
                    )
                }
                IconButton(onClick = { frameIndex = (frameIndex + 1) % frames.size }) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.next), tint = KaspaTeal)
                }
                Text(tr(R.string.kp_frame_lld_lld_5cb10, frameIndex + 1, frames.size), color = Color(0xFF6B6B70), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * One frame as a byte-mode QR at one pixel per module, no quiet zone (the white card is the
 * margin) - the shared [QrModules] encoding, the same as [rememberQrBitmapPainter]'s ByteArray
 * form (ISO-8859-1 round trip, KasSigner's raw-byte QR).
 */
private fun encodeQrModules(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap =
    QrModules.encode(String(bytes, Charsets.ISO_8859_1), margin = 0, byteMode = true)
        ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.WHITE) }
            .asImageBitmap()

/**
 * Scans a KasSigner animated multi-frame QR sequence (a signed KSPT response, or anything else
 * chunked with [QrFrameChunker]) and reassembles it - iOS's `MultiFrameQRScannerView`, the same
 * sheet as [QrScannerSheet] titled "Scan Signed Transaction": "Point camera at the KasSigner
 * screen" until the first frame, then a dot per frame (the accent once seen) over "3 / 5 frames".
 * It keeps scanning until every frame has been seen, then hands the bytes over and slides down.
 */
@Composable
internal fun MultiFrameQrScannerSheet(
    isComplete: (ByteArray) -> Boolean,
    onComplete: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val latestComplete by androidx.compose.runtime.rememberUpdatedState(onComplete)
    val accumulator = remember { QrFrameChunker.Accumulator(isComplete) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var receivedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val done = remember { AtomicBoolean(false) }
    ColdCameraScannerSheet(
        title = stringResource(R.string.scan_signed_transaction),
        deniedMessage = stringResource(R.string.qr_enable_camera_access_signed),
        onDismiss = onDismiss,
        onResult = { result, close ->
            if (done.get()) return@ColdCameraScannerSheet
            val bytes = extractRawBytes(result) ?: return@ColdCameraScannerSheet
            val complete = accumulator.addFrame(bytes)
            if (complete == null) {
                progress = accumulator.progress
                receivedIndices = accumulator.receivedFrameIndices
            } else if (done.compareAndSet(false, true)) {
                latestComplete(complete)
                close()
            }
        },
    ) {
        val (received, total) = progress ?: (0 to 0)
        if (total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                repeat(total) { i ->
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (i in receivedIndices) KaspaTeal else Color.White.copy(alpha = 0.4f))
                    )
                }
            }
            com.kachat.app.ui.screens.ScannerPlate(stringResource(R.string.qr_frames_progress, received, total))
        } else {
            com.kachat.app.ui.screens.ScannerPlate(stringResource(R.string.qr_point_camera_kassigner))
        }
    }
}

/**
 * Recovers the exact original bytes from a decoded byte-mode QR (a KSPT frame), trying a few
 * fallbacks since ZXing's [Result.getRawBytes] isn't reliably populated for every byte-mode result:
 * the raw [ResultMetadataType.BYTE_SEGMENTS], then [Result.getRawBytes], then the text re-encoded
 * as ISO-8859-1 (1 byte : 1 char : 1 byte, as both ends encode it).
 */
private fun extractRawBytes(result: Result): ByteArray? {
    @Suppress("UNCHECKED_CAST")
    val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
    if (!segments.isNullOrEmpty()) {
        return segments.reduce { acc, segment -> acc + segment }
    }
    result.rawBytes?.let { if (it.isNotEmpty()) return it }
    return result.text?.toByteArray(Charsets.ISO_8859_1)
}


/**
 * The scanner sheet (KaChat's `CameraScannerSheet`, which QrScannerScreen.kt keeps private): an
 * [IosFullSheet] that swipes away, its inline bar ([title], Cancel leading) over the camera, the 250
 * white frame, and [bottom] 50 above the bottom edge; camera access asked first, with Open Settings
 * when refused. [onResult] gets every decode and `close`, which slides the sheet down.
 */
@Composable
private fun ColdCameraScannerSheet(
    title: String,
    deniedMessage: String,
    onDismiss: () -> Unit,
    onResult: (Result, close: () -> Unit) -> Unit,
    bottom: @Composable () -> Unit,
) {
    val latestResult by androidx.compose.runtime.rememberUpdatedState(onResult)
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = true) { close ->
        androidx.activity.compose.BackHandler(onBack = close)
        val context = LocalContext.current
        var authorized by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        var denied by remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            authorized = granted
            denied = !granted
        }
        LaunchedEffect(Unit) { if (!authorized) launcher.launch(Manifest.permission.CAMERA) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !authorized &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                ) {
                    authorized = true
                    denied = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val colors = LocalAppColors.current
        Box(Modifier.fillMaxSize()) {
            when {
                authorized -> {
                    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
                    CameraQrScanner(modifier = Modifier.fillMaxSize()) { result ->
                        mainExecutor.execute { latestResult(result, close) }
                    }
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(250.dp).border(3.dp, Color.White, RoundedCornerShape(12.dp)))
                        Spacer(Modifier.weight(1f))
                        bottom()
                        Spacer(Modifier.height(50.dp))
                    }
                }
                denied -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(60.dp))
                    Text(stringResource(R.string.qr_camera_access_required), color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        deniedMessage,
                        color = colors.textSecondary,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Button(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                    ) {
                        Text(stringResource(R.string.open_settings))
                    }
                }
                else -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary)
                    Text(stringResource(R.string.qr_requesting_camera_access), color = colors.textSecondary, fontSize = 17.sp)
                }
            }
            SheetNavBar(
                title = title,
                leading = { SheetBarButton(stringResource(R.string.cancel), onClick = close) },
            )
        }
    }
}
