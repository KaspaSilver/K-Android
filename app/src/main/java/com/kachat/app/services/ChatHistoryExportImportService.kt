package com.kachat.app.services

import com.kachat.app.R
import android.content.Context
import android.util.Log
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kachat.app.models.ChatHistoryArchive
import com.kachat.app.models.ChatHistoryArchiveConversation
import com.kachat.app.models.ChatHistoryArchiveMessage
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.GroupRepository
import com.kachat.app.util.MessageProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat-history export/import — file format deliberately matches iOS's `ChatHistoryArchive`
 * JSON schema field-for-field (see [ChatHistoryArchive]), so a file exported from one platform
 * imports cleanly on the other. Scoped to whichever account is active: export pulls only that
 * account's contacts/messages, import attaches everything to that same active account (an
 * archive's own `walletAddress` field is informational only, never used to route data — matches
 * iOS). Import always merges, never wipes — messages that already exist locally (same id) are
 * skipped, not overwritten.
 *
 * Wire constraints that are NOT negotiable (verified against the real decoders on the other
 * platforms — desktop's `ui/app.js` documents the same list):
 *   * iOS decodes `id` as `UUID` and `conversationId` as `UUID?`. A non-UUID string there throws
 *     and takes the WHOLE archive down, so an Android backup would not restore on an iPhone at
 *     all. Android has no UUIDs of its own (a message IS its txId), so every id is a
 *     DETERMINISTIC UUID derived from the txId — same message, same id, every export.
 *   * iOS's JSONDecoder uses `.iso8601`, i.e. `[.withInternetDateTime]` with NO fractional
 *     seconds — `…T12:34:56.789Z` fails to parse. `DateTimeFormatter.ISO_INSTANT` emits exactly
 *     that whenever the millis are non-zero, so every timestamp is truncated to whole seconds.
 *   * `messageType` decodes as a strict enum: handshake | contextual | payment | audio;
 *     `deliveryStatus` as pending | sent | failed | warning. Android's internal spellings
 *     ("comm"/"pay") must never leak into the file.
 *
 * `txId` is written verbatim — it is the real cross-platform identity, and Android's own import
 * keys its rows by it ([toMessageEntity] ignores `id` and `timestamp` entirely).
 */
@Singleton
class ChatHistoryExportImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val groupRepository: GroupRepository,
    private val walletManager: WalletManager,
    private val addressBookManager: AddressBookManager,
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class ImportResult(val importedMessageCount: Int, val conversationCount: Int)

    /**
     * Builds the archive for the active account, ENCRYPTED into the cross-platform v1 backup
     * envelope ([BackupCrypto]). Callers that need a shareable file use [exportChatHistory].
     */
    suspend fun buildArchiveJson(): String {
        val archive = buildLocalArchive()
        return BackupCrypto.encrypt(gson.toJson(archive), backupEncryptionKey(), walletManager.getAddress())
    }

    /**
     * The envelope key for the active account: derived from the identity/chatting-address
     * private key via [WalletManager.getPrivateKeyBytes] — the exact accessor every other
     * identity consumer (ECIES, handshakes, message signing) already funnels through — per the
     * cross-platform derivation in [BackupCrypto.deriveKey].
     */
    private fun backupEncryptionKey(): ByteArray = BackupCrypto.deriveKey(walletManager.getPrivateKeyBytes())

    private suspend fun buildLocalArchive(): ChatHistoryArchive {
        val myAddress = walletManager.getAddress()
        val contactsById = chatRepository.getContacts().first().associateBy { it.id }
        val messages = chatRepository.getAllMessages()
        // Deleted chats are excluded from the export AND their tombstones travel with the
        // archive, so restoring anywhere (fresh install included) never brings them back.
        val deletedIds = chatRepository.getAllDeletedContactIds().toSet()

        val conversations = messages
            .groupBy { it.contactId }
            .mapNotNull { (contactId, contactMessages) ->
                if (contactId in deletedIds) return@mapNotNull null
                // Pending placeholders are transient local-only state, not confirmed history.
                // The provisional-id check matters just as much as the status check: a FAILED
                // optimistic send still carries its synthetic "pending_<uuid>" id, and exporting
                // it published that id as the row's txId in the shared archive. The union merge
                // then kept that entry forever, and once a successful Retry replaced the local
                // row with the real txId, the txId dedupe could no longer see the pair, so every
                // later mirror import resurrected a stalled twin next to the delivered message.
                val exportable = contactMessages.filter {
                    it.deliveryStatus != "pending" && !MessageEntity.isProvisionalId(it.id)
                }
                if (exportable.isEmpty()) return@mapNotNull null
                ChatHistoryArchiveConversation(
                    // Android has no conversation identity of its own; the contact address is the
                    // identity, so the id is derived from it (stable across exports, and a real
                    // UUID because iOS decodes this field as `UUID?`).
                    conversationId = archiveUuid("", "conversation:$contactId"),
                    contactAddress = contactId,
                    contactAlias = contactsById[contactId]?.alias,
                    contactPhoto = contactPhotoForArchive(contactsById[contactId]),
                    unreadCount = exportable.count { it.direction == "received" && !it.isRead },
                    messages = exportable.map { toArchiveMessage(it, myAddress) }
                )
            }

        // Groups ARE backed up again — now including decrypted message history (not just keys),
        // so message history survives even if the indexer has pruned old messages.
        return ChatHistoryArchive(
            exportedAt = isoSeconds(System.currentTimeMillis()),
            walletAddress = myAddress,
            conversations = conversations,
            groups = groupRepository.exportArchiveGroups(),
            deletedContactAddresses = deletedIds.sorted().takeIf { it.isNotEmpty() },
            addressBook = addressBookManager.archiveEntries().takeIf { it.isNotEmpty() },
            addressBookDeleted = addressBookManager.archiveTombstones().takeIf { it.isNotEmpty() },
        )
    }

    /** The contact's cross-platform photo (one restored from another device), or null. KaChat no
     *  longer reads the phone's Contacts, so no Contacts-app photo is rendered (iOS 00767a4). */
    private fun contactPhotoForArchive(contact: ContactEntity?): String? =
        contact?.backupPhotoBase64?.takeIf { it.isNotBlank() }

    /** Builds the archive for the active account (encrypted into the v1 backup envelope, like every
     *  cloud copy), writes it to app-private cache, and returns a content:// URI ready to hand to a
     *  share sheet. [importChatHistory] accepts only this format: a plaintext file is refused. */
    suspend fun exportChatHistory(): Uri {
        val exportDir = File(context.cacheDir, "chat_exports").apply { mkdirs() }
        // The archive carries the permanent group decryption keys (sealed, but still), and
        // nothing needs an earlier export once its share sheet is gone - so previous exports
        // are removed rather than left in the cache directory indefinitely (iOS deletes the
        // temp file when the share sheet dismisses).
        exportDir.listFiles()?.forEach { runCatching { it.delete() } }
        val fileTimestamp = isoSeconds(System.currentTimeMillis()).replace(":", "-")
        val file = File(exportDir, "kachat-history-$fileTimestamp.json")
        file.writeText(buildArchiveJson())

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Reads a file URI (from the local file picker) and delegates to [importChatHistory]. */
    suspend fun importChatHistory(uri: Uri): ImportResult {
        val json = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            ?: throw IllegalStateException("Could not read the selected file")
        return importChatHistory(json)
    }

    /**
     * Parses and merges an archive JSON string into the active account's local data, for the
     * local file-picker import. Throws with a user-facing message on any validation failure.
     *
     * [onConversationProgress] (optional) fires as `(done, total)` after each conversation's
     * messages land (real work, not simulated) — total counts the archive's non-empty
     * conversations. Drives the blocking restore modal's determinate bar
     * (see [BackupRestoreCoordinator]).
     *
     * Accepts only a v1 encrypted envelope ([BackupCrypto.isEnvelope]), which is all KaChat
     * writes: it is walletHint-checked (a foreign wallet's file is refused without decrypting)
     * and decrypted first — a wrong-key or corrupt file throws
     * [BackupCrypto.DECRYPT_FAILED_MESSAGE]. A plaintext archive is refused
     * ([R.string.archive_not_encrypted]): nothing in it is checked against the chain or a key,
     * so its messages, contacts and groups would be taken as they are from anyone who sent it
     * (iOS d23e6f7a `EnvelopeError.notEncrypted`).
     */
    suspend fun importChatHistory(
        json: String,
        onConversationProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ImportResult {
        // A switch of account mid-import must not land this archive in the next account's chats:
        // the decode and the reads below take seconds, and nothing else stops the import (iOS
        // bfe9f022). Not a verdict on the file: the watcher tries again.
        val importOwner = walletManager.getAddress()
        fun ensureSameAccount() {
            if (runCatching { walletManager.getAddress() }.getOrNull() != importOwner) {
                throw IllegalStateException(context.getString(R.string.the_active_account_changed_switch_back_to))
            }
        }
        // Every "this file can't be imported" verdict below is an [UnreadableBackupException]
        // (messages unchanged for the restore screens), so the change watcher can tell a file it
        // has judged - and should not download again until it changes (NEXTCLOUD_SYNC.md §6) -
        // from a failure worth retrying.
        val archiveJson = if (BackupCrypto.isEnvelope(json)) {
            val hint = BackupCrypto.envelopeWalletHint(json)
            if (hint != null && hint != BackupCrypto.walletHint(walletManager.getAddress())) {
                throw UnreadableBackupException("This backup belongs to a different account.")
            }
            // Key first, outside the try: a key that can't be loaded is this device's problem,
            // not a verdict on the file.
            val key = backupEncryptionKey()
            try {
                BackupCrypto.decrypt(json, key)
            } catch (e: IllegalStateException) {
                throw UnreadableBackupException(e.message ?: BackupCrypto.DECRYPT_FAILED_MESSAGE)
            }
        } else {
            // Only an encrypted export, as on iOS: a plaintext file could have been written by anyone.
            throw UnreadableBackupException(context.getString(R.string.archive_not_encrypted))
        }
        val archive = try {
            gson.fromJson(archiveJson, ChatHistoryArchive::class.java) ?: throw IllegalStateException("empty")
        } catch (e: Exception) {
            throw UnreadableBackupException("This file isn't a valid chat history export")
        }
        if (archive.schemaVersion != ChatHistoryArchive.CURRENT_SCHEMA_VERSION) {
            throw UnreadableBackupException("This export was made with an incompatible app version")
        }
        ensureSameAccount()

        // The Address Book is per wallet: only this wallet's archive (or an unstamped one) fills
        // it (iOS 00767a4).
        val archiveWallet = archive.walletAddress.orEmpty().trim().lowercase()
        val currentWallet = walletManager.getAddress()
        if (archiveWallet.isEmpty() || archiveWallet == currentWallet.lowercase()) {
            addressBookManager.importFromArchive(
                walletAddress = currentWallet,
                entries = archive.addressBook.orEmpty(),
                tombstones = archive.addressBookDeleted.orEmpty()
            )
        }
        if (archive.conversations.all { it.messages.isEmpty() }) {
            throw UnreadableBackupException("This file has no chat history to import")
        }

        val myAddress = walletManager.getAddress()
        var importedCount = 0
        var conversationCount = 0
        val restoredTxIds = HashSet<String>()

        val archivedTombstones = archive.deletedContactAddresses.orEmpty().toSet()
        val progressTotal = archive.conversations.count { it.messages.isNotEmpty() }
        var progressDone = 0
        for (conversation in archive.conversations) {
            if (conversation.messages.isEmpty()) continue
            ensureSameAccount()
            progressDone++
            val contactAddress = conversation.contactAddress
            // Never resurrect a deleted chat: honor this device's tombstones AND the ones the
            // archive itself carries (covers restoring onto a fresh install). Skipped
            // conversations still count as progress — done/total must reach total.
            if (contactAddress in archivedTombstones || chatRepository.hasDeletionTombstone(contactAddress)) {
                onConversationProgress?.invoke(progressDone, progressTotal)
                continue
            }
            // A chat reopened after a deletion keeps what came before it deleted: its floor
            // (iOS 4b00a5f). The old messages, your own sent ones included, would otherwise make
            // the new request read as a chat you had already accepted.
            val deletionFloor = chatRepository.deletionFloor(contactAddress)

            val importedPhoto = conversation.contactPhoto?.takeIf { it.isNotBlank() }
            // A .kas domain is nobody's name since 5.2: a backup still carrying one (an older build
            // or another platform wrote it) never puts it back - a name removed here stays removed
            // (iOS ce0b636).
            val importedAlias = conversation.contactAlias?.trim()?.takeIf { it.isNotEmpty() && !com.kachat.app.models.isKasDomainName(it) }
            val existingContact = chatRepository.getContact(contactAddress)
            if (existingContact == null) {
                chatRepository.addContact(
                    ContactEntity(
                        id = contactAddress,
                        walletAddress = myAddress,
                        alias = importedAlias,
                        knsName = null,
                        publicKeyHex = null,
                        backupPhotoBase64 = importedPhoto
                    )
                )
            } else {
                var updated = existingContact
                if (existingContact.alias.isNullOrBlank() && !importedAlias.isNullOrBlank()) {
                    updated = updated.copy(alias = importedAlias)
                }
                // Adopt a backed-up photo only when this device has none for the contact yet.
                if (updated.backupPhotoBase64.isNullOrBlank() && importedPhoto != null) {
                    updated = updated.copy(backupPhotoBase64 = importedPhoto)
                }
                if (updated != existingContact) chatRepository.addContact(updated)
            }

            // iOS-style counts: the summary reports what the archive RESTORED (distinct txIds
            // across this conversation, whether or not the rows already existed locally), so a
            // re-restore of the same backup still reads "Restored 812 messages from 12 chats"
            // instead of zeros.
            var restoredAny = false
            for (archiveMessage in conversation.messages) {
                ensureSameAccount()
                // "\ud83d\udce4 Sent via another device" placeholders never surface on any platform -
                // skip them at import; an archive that later carries the real body inserts it then.
                if (com.kachat.app.models.MessageEntity.isSentPlaceholder(archiveMessage.content)) continue
                // Phantom rows never import: a provisional "pending_<uuid>" txId (a failed
                // optimistic send an older build exported) or a blank txId is not an on-chain
                // identity, and inserting such a row is exactly what materialized the stalled
                // "still sending" twins next to their delivered copies.
                val archiveTxId = archiveMessage.txId.trim()
                if (archiveTxId.isEmpty() || com.kachat.app.models.MessageEntity.isProvisionalId(archiveTxId)) {
                    Log.i(TAG, "Import skipped phantom archive row (txId=${archiveTxId.take(20).ifEmpty { "<blank>" }})")
                    continue
                }
                if (chatRepository.isBelowDeletionFloor(deletionFloor, archiveTxId, archiveMessage.blockTime)) continue
                if (restoredTxIds.add(archiveTxId)) importedCount++
                restoredAny = true
                val entity = toMessageEntity(archiveMessage, contactAddress, myAddress)
                val existing = chatRepository.getMessage(entity.id)
                // A stored "[Encrypted message]" is a hidden stand-in: the archive's real body
                // replaces it (iOS preferMessage), and the message appears.
                if (existing != null && !(existing.isHiddenPlaceholder && !entity.isHiddenPlaceholder)) {
                    // Healer: the archive proves this txId was broadcast, so a local copy still
                    // stuck at pending/failed (a finalize that never ran) flips to sent in place.
                    if (existing.direction == "sent" && existing.deliveryStatus != "sent" && entity.deliveryStatus == "sent") {
                        chatRepository.updateMessageStatus(entity.id, "sent")
                        Log.i(TAG, "Import healed stuck delivery status to sent (txId=${entity.id.take(16)})")
                    }
                    continue
                }
                // Outgoing archive rows can be THIS device's own send coming back around under
                // its real txId (another device saw it on-chain and uploaded it) while the local
                // copy still sits under its provisional "pending_<uuid>" placeholder id — the
                // send flow's finalize step never ran (process death / cancelled coroutine /
                // local timeout on a broadcast that actually landed). A plain insert would
                // create a delivered twin next to a forever-"sending" placeholder. Instead the
                // placeholder is upgraded in place to the real txId + "sent".
                if (archiveMessage.isOutgoing) {
                    when (val match = chatRepository.matchProvisionalOutgoing(contactAddress, entity.plaintextBody, entity.blockTimestamp)) {
                        is ChatRepository.ProvisionalMatch.Exact -> {
                            chatRepository.upgradeProvisionalMessage(match.row, entity)
                            continue
                        }
                        is ChatRepository.ProvisionalMatch.Sole -> {
                            // Content drifted (metadata, formatting) but there is exactly one
                            // in-flight candidate in the window and the archive row is not older
                            // than it: the archive's delivered copy wins, the placeholder goes
                            // away. If the placeholder was a genuinely different in-flight send,
                            // its own finalize re-inserts the real row moments later, so nothing
                            // is lost either way.
                            chatRepository.collapseProvisionalInto(match.row, entity)
                            continue
                        }
                        is ChatRepository.ProvisionalMatch.Ambiguous -> {
                            Log.i(TAG, "Import: multiple provisional candidates for txId=${entity.id.take(16)}; inserted without collapsing")
                        }
                        ChatRepository.ProvisionalMatch.None -> Unit
                    }
                }
                chatRepository.insertMessage(entity)
            }
            if (restoredAny) conversationCount++
            onConversationProgress?.invoke(progressDone, progressTotal)
        }

        // Groups (cross-platform recovery): restore full group key material so this device
        // recovers admin groups it created elsewhere as well as member ones. Optional - older
        // archives omit it.
        ensureSameAccount()
        archive.groups?.takeIf { it.isNotEmpty() }?.let { groupRepository.importArchiveGroups(it) }

        return ImportResult(importedMessageCount = importedCount, conversationCount = conversationCount)
    }

    companion object {
        private const val TAG = "ChatHistoryImport"
        private val VALID_DELIVERY_STATUSES = setOf("pending", "sent", "failed", "warning")
        private val VALID_MESSAGE_TYPES = setOf("handshake", "contextual", "payment", "audio")

        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        // -----------------------------------------------------------------------------
        // Wire-format helpers (kept byte-identical to desktop's ui/app.js so the two
        // platforms derive the SAME id for the same message and never fight over it)
        // -----------------------------------------------------------------------------

        /**
         * Deterministic RFC-4122 UUID from an arbitrary string (xmur3 seed -> 4 words), so
         * re-exporting the same message always produces the same `id` and the other platforms'
         * id-keyed dedupe keeps working across backups. Ported verbatim from desktop's
         * `derivedArchiveUuid` (JS `Math.imul`/`>>>` map exactly onto Kotlin `Int` arithmetic).
         */
        internal fun derivedArchiveUuid(seed: String): String {
            var h = 1779033703 xor seed.length
            for (element in seed) {
                h = (h xor element.code) * 3432918353L.toInt()
                h = (h shl 13) or (h ushr 19)
            }
            val builder = StringBuilder(32)
            repeat(4) {
                h = (h xor (h ushr 16)) * 2246822507L.toInt()
                h = (h xor (h ushr 13)) * 3266489909L.toInt()
                h = h xor (h ushr 16)
                builder.append((h.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0'))
            }
            val nibbles = builder.toString().toCharArray()
            nibbles[12] = '4'                                                           // RFC 4122 version
            nibbles[16] = "89ab"[Character.digit(nibbles[16], 16) and 3]                // RFC 4122 variant
            val flat = String(nibbles)
            return "${flat.substring(0, 8)}-${flat.substring(8, 12)}-${flat.substring(12, 16)}-" +
                "${flat.substring(16, 20)}-${flat.substring(20, 32)}"
        }

        /** Passes a real UUID through untouched, otherwise derives one (from [value] when it has one, else [seed]). */
        internal fun archiveUuid(value: String?, seed: String): String {
            val raw = value?.trim().orEmpty()
            if (UUID_PATTERN.matches(raw)) return raw.lowercase()
            return derivedArchiveUuid(raw.ifEmpty { seed })
        }

        /**
         * Whole-second ISO8601 (`2026-08-17T12:34:56Z`). `DateTimeFormatter.ISO_INSTANT` alone is
         * NOT safe here: it appends `.123` whenever the millis are non-zero, and iOS's `.iso8601`
         * decoding strategy rejects fractional seconds outright.
         */
        internal fun isoSeconds(epochMs: Long): String {
            val instant = Instant.ofEpochMilli(if (epochMs > 0) epochMs else System.currentTimeMillis())
            return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))
        }

        internal fun archiveMessageType(entityType: String): String = when (entityType) {
            MessageProtocol.TYPE_HANDSHAKE -> "handshake"
            MessageProtocol.TYPE_COMM -> "contextual"
            MessageProtocol.TYPE_PAY -> "payment"
            // Anything else would be an Android-internal spelling leaking into a file iOS decodes
            // as a strict enum — that throws and takes the whole archive down, so never emit it.
            else -> if (entityType in VALID_MESSAGE_TYPES) entityType else "contextual"
        }

        /** Android has no distinct "audio message" type yet — those import as a regular contextual message. */
        internal fun entityMessageType(archiveType: String): String = when (archiveType) {
            "handshake" -> MessageProtocol.TYPE_HANDSHAKE
            "contextual" -> MessageProtocol.TYPE_COMM
            "payment" -> MessageProtocol.TYPE_PAY
            "audio" -> MessageProtocol.TYPE_COMM
            else -> MessageProtocol.TYPE_COMM
        }

        private fun archiveDeliveryStatus(status: String): String =
            if (status in VALID_DELIVERY_STATUSES) status else "sent"

        internal fun toArchiveMessage(entity: MessageEntity, myAddress: String): ChatHistoryArchiveMessage {
            val isOutgoing = entity.direction == "sent"
            return ChatHistoryArchiveMessage(
                // entity.id IS the txId; iOS needs a UUID here, so publish a deterministic one.
                id = archiveUuid(entity.id, "${entity.contactId}:${entity.id}"),
                txId = entity.id,
                senderAddress = if (isOutgoing) myAddress else entity.contactId,
                receiverAddress = if (isOutgoing) entity.contactId else myAddress,
                content = entity.plaintextBody ?: "",
                timestamp = isoSeconds(entity.blockTimestamp),
                blockTime = entity.blockTimestamp,
                isOutgoing = isOutgoing,
                messageType = archiveMessageType(entity.type),
                deliveryStatus = archiveDeliveryStatus(entity.deliveryStatus)
            )
        }

        /** Imported history is never marked unread — the archive format tracks unread only as a per-conversation count, not per message, so there's nothing meaningful to restore. */
        internal fun toMessageEntity(archiveMessage: ChatHistoryArchiveMessage, contactId: String, myAddress: String): MessageEntity {
            return MessageEntity(
                id = archiveMessage.txId.trim(),
                contactId = contactId,
                walletAddress = myAddress,
                type = entityMessageType(archiveMessage.messageType),
                direction = if (archiveMessage.isOutgoing) "sent" else "received",
                plaintextBody = archiveMessage.content,
                encryptedPayload = "",
                amountSompi = null,
                blockTimestamp = archiveMessage.blockTime,
                isRead = true,
                // The importer only ever reaches this for rows with a real txId, and a real txId
                // means the send WAS broadcast: another device's stale "pending"/"failed"
                // snapshot must not materialize here as an eternally-waiting row this device
                // has no send flow to ever resolve. Only "warning" (payment verification) is a
                // real terminal state worth carrying over.
                deliveryStatus = if (archiveMessage.deliveryStatus == "warning") "warning" else "sent"
            )
        }
    }
}

/**
 * A backup file [ChatHistoryExportImportService.importChatHistory] judged and refused - another
 * account's, undecryptable, not an archive, an incompatible schema, or empty. Still an
 * [IllegalStateException] with the same user-facing message as before; the type is what lets the
 * change watcher record that version's ETag instead of downloading it on every poll
 * (NEXTCLOUD_SYNC.md §6).
 */
class UnreadableBackupException(message: String) : IllegalStateException(message)
