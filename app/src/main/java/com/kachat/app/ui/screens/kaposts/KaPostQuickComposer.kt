package com.kachat.app.ui.screens

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.kachat.app.R
import com.kachat.app.services.KaPostsService
import com.kachat.app.services.WalletManager
import com.kachat.app.viewmodels.KaPostsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the quick composer posts with. */
@HiltViewModel
class KaPostQuickComposerViewModel @Inject constructor(
    private val kaPostsService: KaPostsService,
    private val walletManager: WalletManager,
) : ViewModel() {
    /**
     * Sends [text] as a new post now (no undo window: there is no feed here for one), on a scope
     * that outlives the composer and the game it was opened over; [onResult] gets the post's
     * txid, or why it didn't go, on the main thread.
     */
    fun post(text: String, mentions: suspend (String) -> List<String>, onResult: (Result<String>) -> Unit) {
        val owner = runCatching { walletManager.getAddress() }.getOrDefault("")
        postScope.launch {
            val result = try {
                // The post signs the keys of the people it @'s by username, as the feed's posts
                // do (iOS `KaPostsView.mentionedPubkeys(in:)`, 2026-11-24): that is what notifies
                // them.
                val mentioned = runCatching { mentions(text) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    emptyList()
                }
                Result.success(kaPostsService.submitPost(text, mentioned, expectedOwner = owner))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("KaPosts", "Quick post failed: ${e.message}")
                Result.failure(e)
            }
            onResult(result)
        }
    }

    private companion object {
        val postScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

/**
 * KaPost's new-post composer over another screen (owner, 2026-11-24; iOS 518fdc8c
 * `KaPostQuickComposer`): KaPlay's Share to KaPost slides it up over the game instead of leaving
 * for KaPost. Post sends the post at once (there is no feed here for the five-second undo); the X
 * closes it and leaves you where you were. The composer shows a challenge link (arcade or bot) as
 * the card the feed will draw.
 *
 * [onPosting]: Post was tapped, the post is on its way. [onResult]: the post's txid, or why it
 * didn't go. [onClose]: the composer has gone (Post or the X).
 */
@Composable
fun KaPostQuickComposer(
    text: String,
    onPosting: () -> Unit = {},
    onResult: (Result<String>) -> Unit,
    onClose: () -> Unit,
) {
    val poster: KaPostQuickComposerViewModel = hiltViewModel()
    val feed: KaPostsViewModel = hiltViewModel()
    KaPostComposerDialog(
        title = stringResource(R.string.kpui_new_post),
        quoted = null,
        initialText = text,
        onDismiss = onClose,
        onSubmit = { body ->
            onPosting()
            onClose()
            poster.post(body, feed::mentionedPubkeysFor, onResult)
        },
        viewModel = feed,
    )
}
