package com.kachat.app.services

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kachat.app.repository.GroupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic background catch-up for GROUP chat only ([GroupRepository.syncGroups]), for a device
 * push doesn't reach. Groups are pushed (watched blinded group ids; invites read straight off the
 * push), so KaChatApplication schedules this job only while the account has groups and the
 * background isn't quiet ([PushState.backgroundQuiet]: no push, signed in, notifications on), and
 * cancels it otherwise ([wanted], audit 2026-11-25 A4). There it is groups' closed-app delivery
 * path, every ~15 minutes, including the group-invite (`gctl_root`) and group-message
 * notifications syncGroups posts itself. Both `gcomm`/`gctl` payloads are self-stash transactions
 * on a queryable identity address, now indexed (`group-messages/by-blinded-group-id`,
 * `group-control/by-sender`), so they fit this short-lived catch-up shape. Signed out, syncGroups
 * returns at once.
 *
 * 1:1 chat, broadcasts, and KaPosts are deliberately NOT synced here: FCM push (see
 * PushRegistrationManager) is their background delivery path, and without push the 1:1 poll loop
 * covers 1:1 chat. Broadcast messages additionally have no queryable per-cursor history
 * to catch up on, only a live block subscription, which doesn't fit a periodic job anyway.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Lazy: it needs the wallet store, so it's built only once the stores are known to open.
    private val groupRepository: dagger.Lazy<GroupRepository>,
    private val pushState: PushState,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Account stores unreadable: the app shows its problem screen when opened; nothing to sync.
        if (!com.kachat.app.util.SecureStores.accountStoresOpen(applicationContext)) return Result.success()
        // Push came back, or nothing could be shown, since this run was scheduled: the app is
        // cancelling the job; this run has nothing to deliver.
        if (pushState.isBackgroundQuiet) return Result.success()
        return try {
            groupRepository.get().syncGroups()
            Result.success()
        } catch (e: Exception) {
            Log.w("SyncWorker", "Periodic group sync failed, will retry next cycle", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "kachat_periodic_sync"

        /** Whether the periodic job should be scheduled: only where no push delivers groups and a
         *  banner could be shown, and only with a group to catch up (audit 2026-11-25 A4). */
        fun wanted(backgroundQuiet: Boolean, hasGroups: Boolean): Boolean = !backgroundQuiet && hasGroups
    }
}
