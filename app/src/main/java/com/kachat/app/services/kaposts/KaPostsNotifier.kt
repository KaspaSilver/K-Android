package com.kachat.app.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kachat.app.MainActivity
import com.kachat.app.R
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KaPost's local banners: activity on your posts while the app is open (the poller's, iOS
 * 428a797b) and pushes that arrive meanwhile, one per action, and their removal once you have
 * seen the list. KaPost's `NotificationHelper.showKaPosts`
 * / `cancelKaPostsNotifications`, kept here so KaChat's NotificationHelper stays as it is; it posts
 * on KaChat's own message channels ([NotificationHelper.channelFor]).
 *
 * A tap opens MainActivity with [KaPostsIntents] extras: the post ([KaPostsIntents.EXTRA_KAPOST_TXID]),
 * or with none the notifications behind Profile's bell (iOS 148a5331 `NotificationsRoute`).
 */
@Singleton
class KaPostsNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
) {
    /** Action txids already shown: the poller and a push can both see the same action. */
    private val shownActions = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Ids of the banners posted this process - the fallback below API 23. */
    private val shownIds = mutableSetOf<Int>()

    /** Claims [actionTxId] for a banner: false when one was already shown for it. A push handler
     *  calls this before drawing its own, so the two never both show. */
    fun claim(actionTxId: String): Boolean = actionTxId.isBlank() || shownActions.add(actionTxId)

    /** "alice liked your post": a tap opens [postTxId] when known (its thread scrolled to
     *  [focusTxId]), else the notifications list. */
    suspend fun showKaPosts(text: String, actionTxId: String, postTxId: String? = null, focusTxId: String? = null) {
        if (!settings.notificationsEnabled.first()) return
        if (!claim(actionTxId)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(KaPostsIntents.EXTRA_INTERNAL_TOKEN, KaPostsIntents.internalToken(context))
            putExtra(KaPostsIntents.EXTRA_OPEN_KAPOSTS, true)
            if (!postTxId.isNullOrBlank()) putExtra(KaPostsIntents.EXTRA_KAPOST_TXID, postTxId)
            if (!focusTxId.isNullOrBlank()) putExtra(KaPostsIntents.EXTRA_KAPOST_FOCUS_TXID, focusTxId)
        }
        val notificationId = "kaposts_$actionTxId".hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val soundEnabled = settings.notificationSoundEnabled.first()
        val vibrationEnabled = settings.notificationVibrationEnabled.first()
        val notification = NotificationCompat.Builder(context, NotificationHelper.channelFor(soundEnabled, vibrationEnabled))
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle("KaPost")
            // without bidi controls, so a name or post can't reorder what the lock screen shows
            // (iOS 428a797b NotificationService `withoutBidiControls`)
            .setContentText(NotificationHelper.withoutBidiControls(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(NotificationHelper.withoutBidiControls(text)))
            .addExtras(android.os.Bundle().apply {
                if (actionTxId.isNotBlank()) putString(NotificationHelper.EXTRA_TX_ID, actionTxId)
            })
            // iOS threadIdentifier "kaposts": what [cancelKaPostsNotifications] knows it by.
            .setGroup(KAPOSTS_GROUP)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!soundEnabled && !vibrationEnabled)
            .setVibrate(if (vibrationEnabled) longArrayOf(0, 250, 250, 250) else longArrayOf(0))
            .apply { if (!soundEnabled) setSound(null) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            synchronized(shownIds) { shownIds.add(notificationId) }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }

    /**
     * Takes every KaPost banner out of the shade once the list was seen (iOS markSeen clears the
     * "kaposts" thread): ours by their group, the ones FCM drew in the background by their title
     * ("KaPosts" or "KaPost", as the push service sends it).
     */
    fun cancelKaPostsNotifications() {
        val ids = synchronized(shownIds) { shownIds.toList().also { shownIds.clear() } }
        val manager = NotificationManagerCompat.from(context)
        for (id in ids) manager.cancel(id)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val system = context.getSystemService(NotificationManager::class.java) ?: return
            for (sbn in system.activeNotifications) {
                val title = sbn.notification.extras
                    ?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                val isKaPosts = sbn.notification.group == KAPOSTS_GROUP || title == "KaPosts" || title == "KaPost"
                if (isKaPosts) system.cancel(sbn.tag, sbn.id)
            }
        }
    }

    companion object {
        const val KAPOSTS_GROUP = "kaposts"
    }
}

/**
 * The extras a KaPost tap carries into MainActivity. MainActivity is exported, so any app could
 * start it with them: every intent built here carries a per-install random token, and the
 * extras count only when it matches ([isInternalIntent]). KaPost's `NotificationHelper` token.
 */
object KaPostsIntents {
    const val EXTRA_INTERNAL_TOKEN = "kapost_internal_token"
    /** Open KaPost (with [EXTRA_KAPOST_TXID]: that post; without: the notifications list). */
    const val EXTRA_OPEN_KAPOSTS = "open_kaposts"
    const val EXTRA_KAPOST_TXID = "kapost_txid"
    /** A reply's own txid: the opened parent thread scrolls to it. */
    const val EXTRA_KAPOST_FOCUS_TXID = "kapost_focus_txid"

    private const val TOKEN_PREFS = "kapost_notification_token"
    private const val TOKEN_KEY = "token"
    @Volatile private var cachedToken: String? = null

    @Synchronized
    fun internalToken(context: Context): String {
        cachedToken?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(TOKEN_KEY, null) ?: run {
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            bytes.joinToString("") { "%02x".format(it) }.also { prefs.edit().putString(TOKEN_KEY, it).apply() }
        }
        cachedToken = token
        return token
    }

    /** Whether [intent] was built by KaPost's own notifications (see [EXTRA_INTERNAL_TOKEN]). */
    fun isInternalIntent(context: Context, intent: Intent): Boolean {
        val presented = runCatching { intent.getStringExtra(EXTRA_INTERNAL_TOKEN) }.getOrNull() ?: return false
        return java.security.MessageDigest.isEqual(presented.toByteArray(), internalToken(context).toByteArray())
    }
}
