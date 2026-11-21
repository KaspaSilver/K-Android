package com.kachat.app.services.publicchats

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kachat.app.MainActivity
import com.kachat.app.R
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public room notifications, and which room is on screen (iOS `PublicChatService.isViewing`,
 * `acquire`/`release`). Kept beside [NotificationHelper] rather than in it so Public Chats adds no
 * code there; it shares its channels, its settings and its per-transaction dedupe
 * ([NotificationHelper.claimForBanner]), so a room message seen by the block scan and by a push
 * banners once.
 *
 * A tap opens MainActivity with [EXTRA_CHANNEL_NAME]: the room, in the Chats list's navigation.
 */
@Singleton
class BroadcastNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
    private val notificationHelper: NotificationHelper,
) {
    private val activeChannel = MutableStateFlow<String?>(null)

    /** The room on screen, or null. */
    val activeChannelName: StateFlow<String?> = activeChannel.asStateFlow()

    val isAppInForeground: Boolean get() = notificationHelper.isAppInForeground

    fun setActiveChannel(channelName: String?) {
        activeChannel.value = channelName
    }

    /** Only [channelName]'s screen: another room's screen may already have taken over. */
    fun clearActiveChannel(channelName: String) {
        activeChannel.compareAndSet(channelName, null)
    }

    /** The room on screen, if any - the foreground sweep skips it (it has its own poll), and its
     *  unread count reads 0 (iOS `unreadCount` while `isViewing`). */
    fun isViewingChannel(channelName: String): Boolean = activeChannel.value == channelName

    /**
     * A banner for a new room message. Every room banner comes through here, from the block scan
     * and from a push alike, so a reply, photo or reaction envelope never reaches the shade as raw
     * JSON ([notificationText]). Not while that room is on screen with the app in front (iOS
     * KaPostsApp willPresent): a room left open behind the home button notifies as usual.
     */
    suspend fun showBroadcast(channelName: String, title: String, text: String, dedupeTxId: String? = null) {
        val bodyText = notificationText(text)
        if (activeChannel.value == channelName && notificationHelper.isAppInForeground) return
        if (!settings.notificationsEnabled.first()) return
        if (!dedupeTxId.isNullOrBlank() && !notificationHelper.claimForBanner(dedupeTxId)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHANNEL_NAME, channelName)
        }
        val id = notificationId(channelName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val soundEnabled = settings.notificationSoundEnabled.first()
        val vibrationEnabled = settings.notificationVibrationEnabled.first()
        val notification = NotificationCompat.Builder(context, NotificationHelper.channelFor(soundEnabled, vibrationEnabled))
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!soundEnabled && !vibrationEnabled)
            .setVibrate(if (vibrationEnabled) longArrayOf(0, 250, 250, 250) else longArrayOf(0))
            .apply { if (!soundEnabled) setSound(null) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted: skip rather than crash.
        }
    }

    /** Takes down [channelName]'s banners: this one's, and any the system drew from a push
     *  ("#room" as the title, iOS clears thread broadcast:<room>). */
    fun clearDelivered(channelName: String) {
        val ownId = notificationId(channelName)
        runCatching { NotificationManagerCompat.from(context).cancel(ownId) }
        runCatching {
            val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return@runCatching
            for (sbn in manager.activeNotifications) {
                val title = sbn.notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                if (sbn.id == ownId || title == "#$channelName") manager.cancel(sbn.tag, sbn.id)
            }
        }
    }

    companion object {
        /** The room a tapped room notification opens (MainActivity hands it to the Chats list). */
        const val EXTRA_CHANNEL_NAME = "channel_name"

        fun notificationId(channelName: String): Int = "bcast:$channelName".hashCode()

        /** What a room message reads as in a banner: never an envelope's raw JSON. */
        fun notificationText(content: String): String {
            com.kachat.app.util.MessageReaction.parseOrNull(content)?.let { return "Reacted ${it.emoji}" }
            if (com.kachat.app.util.MessageEdit.parseOrNull(content) != null) return "Edited a message"
            val unwrapped = com.kachat.app.util.MessageReply.parseOrNull(content)?.text ?: content
            com.kachat.app.util.InlineMediaSniff.mimeType(unwrapped)?.let { mime ->
                return when {
                    mime.startsWith("audio/") -> "🎤 Audio message"
                    mime.startsWith("image/") -> "📷 Photo"
                    mime.startsWith("video/") -> "🎬 Video"
                    else -> "📎 File"
                }
            }
            if (com.kachat.app.util.ChessMessage.parseOrNull(unwrapped) != null) return "♟️ Chess game"
            return unwrapped
        }
    }
}
