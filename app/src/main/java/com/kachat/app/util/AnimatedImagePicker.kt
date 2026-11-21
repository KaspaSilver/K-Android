package com.kachat.app.util

import android.content.Context
import android.content.Intent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The photo picker for the composer's GIF tile (iOS d7c56280, `PHPickerFilter` "animated
 * images"): it offers GIF, WebP, PNG and HEIF ([GifPrep.PICKER_MIME_TYPES]), and the pick is
 * kept only when [GifPrep.isAnimated] says it has more than one frame. Launch it with
 * [request]. A picker that doesn't filter by those types shows every image; the check is the same.
 */
class PickAnimatedImage : ActivityResultContracts.PickVisualMedia() {
    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_MIME_TYPES, GifPrep.PICKER_MIME_TYPES)

    companion object {
        /** Images only; the types come from [createIntent]. */
        fun request(): PickVisualMediaRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    }
}
