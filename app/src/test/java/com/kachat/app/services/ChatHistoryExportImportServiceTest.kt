package com.kachat.app.services

import com.kachat.app.models.ChatHistoryArchiveMessage
import com.kachat.app.models.MessageEntity
import com.kachat.app.util.MessageProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

class ChatHistoryExportImportServiceTest {

    private val myAddress = "kaspa:me"
    private val contactAddress = "kaspa:them"

    private fun message(
        id: String = "txid1",
        type: String = MessageProtocol.TYPE_COMM,
        direction: String = "sent",
        deliveryStatus: String = "sent"
    ) = MessageEntity(
        id = id,
        contactId = contactAddress,
        walletAddress = myAddress,
        type = type,
        direction = direction,
        plaintextBody = "hello",
        encryptedPayload = "deadbeef",
        amountSompi = null,
        blockTimestamp = 1_700_000_000_000L,
        isRead = false,
        deliveryStatus = deliveryStatus
    )

    // --- type mapping ---------------------------------------------------------------

    @Test
    fun `entity type maps to the matching archive type`() {
        assertEquals("handshake", ChatHistoryExportImportService.archiveMessageType(MessageProtocol.TYPE_HANDSHAKE))
        assertEquals("contextual", ChatHistoryExportImportService.archiveMessageType(MessageProtocol.TYPE_COMM))
        assertEquals("payment", ChatHistoryExportImportService.archiveMessageType(MessageProtocol.TYPE_PAY))
    }

    @Test
    fun `archive type maps back to the matching entity type`() {
        assertEquals(MessageProtocol.TYPE_HANDSHAKE, ChatHistoryExportImportService.entityMessageType("handshake"))
        assertEquals(MessageProtocol.TYPE_COMM, ChatHistoryExportImportService.entityMessageType("contextual"))
        assertEquals(MessageProtocol.TYPE_PAY, ChatHistoryExportImportService.entityMessageType("payment"))
    }

    @Test
    fun `an iOS-only audio type imports as a regular contextual message`() {
        assertEquals(MessageProtocol.TYPE_COMM, ChatHistoryExportImportService.entityMessageType("audio"))
    }

    // --- toArchiveMessage -------------------------------------------------------------

    @Test
    fun `an outgoing message maps sender to me and receiver to the contact`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(direction = "sent"), myAddress)
        assertEquals(myAddress, archived.senderAddress)
        assertEquals(contactAddress, archived.receiverAddress)
        assertTrue(archived.isOutgoing)
    }

    @Test
    fun `an incoming message maps sender to the contact and receiver to me`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(direction = "received"), myAddress)
        assertEquals(contactAddress, archived.senderAddress)
        assertEquals(myAddress, archived.receiverAddress)
        assertTrue(!archived.isOutgoing)
    }

    @Test
    fun `txId is written verbatim and id is a real UUID, since iOS decodes id as UUID`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(id = "abc123"), myAddress)
        assertEquals("abc123", archived.txId)
        assertTrue("id must be an RFC-4122 UUID, was ${archived.id}", archived.id.matches(UUID_REGEX))
        // Deterministic: a second export of the same message must publish the same id.
        assertEquals(archived.id, ChatHistoryExportImportService.toArchiveMessage(message(id = "abc123"), myAddress).id)
    }

    /**
     * The derived-id hash is a port of desktop's `derivedArchiveUuid` (ui/app.js). These expected
     * values were produced by running the JS original — if the two ever drift, the same message
     * would get two different ids depending on which device wrote the shared file last.
     */
    @Test
    fun `derived UUIDs match desktop's implementation exactly`() {
        assertEquals("c578303f-bb88-4b3d-8fbc-287ef1e92f90", ChatHistoryExportImportService.derivedArchiveUuid("abc123"))
        assertEquals("81106ed0-52ea-481b-91bd-4eb44339dc86", ChatHistoryExportImportService.derivedArchiveUuid("conversation:kaspa:them"))
        assertEquals("73ce14cf-68c2-4703-b0a4-853484e0734e", ChatHistoryExportImportService.derivedArchiveUuid("0".repeat(64)))
        assertEquals("09f45f69-9b9a-4489-9921-c94c508f729e", ChatHistoryExportImportService.derivedArchiveUuid(""))
        assertEquals("5ce33da7-53d4-492a-b5f4-4ca4c305477d", ChatHistoryExportImportService.derivedArchiveUuid("üñí✓"))
    }

    @Test
    fun `an id that is already a UUID is passed through untouched`() {
        val existing = "81106ed0-52ea-481b-91bd-4eb44339dc86"
        assertEquals(existing, ChatHistoryExportImportService.archiveUuid(existing, "ignored"))
    }

    @Test
    fun `timestamps are whole-second ISO8601, since iOS rejects fractional seconds`() {
        // 1_700_000_000_123 has non-zero millis — ISO_INSTANT would emit ".123" and iOS would throw.
        val archived = ChatHistoryExportImportService.toArchiveMessage(
            message().copy(blockTimestamp = 1_700_000_000_123L),
            myAddress
        )
        assertEquals("2023-11-14T22:13:20Z", archived.timestamp)
    }

    @Test
    fun `an unknown internal type never leaks into the file as-is`() {
        assertEquals("contextual", ChatHistoryExportImportService.archiveMessageType("something_new"))
    }

    @Test
    fun `an unknown local delivery status is written as sent`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(deliveryStatus = "queued"), myAddress)
        assertEquals("sent", archived.deliveryStatus)
    }

    // --- backup envelope detection ------------------------------------------------------

    private val ownHint = BackupCrypto.walletHint(myAddress)

    private fun envelope(hint: String? = ownHint, version: Int = 1) =
        """{"kachatEncryptedBackup":$version,"cipher":"aes-256-gcm","nonce":"AAAAAAAAAAAAAAAA","ciphertext":"BBBB"""" +
            (if (hint != null) ""","walletHint":"$hint"}""" else "}")

    @Test
    fun `envelope detection reads the marker version and walletHint`() {
        assertTrue(BackupCrypto.isEnvelope(envelope()))
        assertFalse(BackupCrypto.isEnvelope(envelope(version = 2)))
        assertEquals(2, BackupCrypto.envelopeVersion(envelope(version = 2)))
        assertNull(BackupCrypto.envelopeVersion("""{"schemaVersion":1,"walletAddress":"$myAddress","conversations":[]}"""))
        assertEquals(ownHint, BackupCrypto.envelopeWalletHint(envelope()))
        assertNull(BackupCrypto.envelopeWalletHint(envelope(hint = null)))
    }

    // --- toMessageEntity ---------------------------------------------------------------

    private fun archiveMessage(
        isOutgoing: Boolean = true,
        deliveryStatus: String = "sent",
        messageType: String = "contextual"
    ) = ChatHistoryArchiveMessage(
        id = "txid1",
        txId = "txid1",
        senderAddress = if (isOutgoing) myAddress else contactAddress,
        receiverAddress = if (isOutgoing) contactAddress else myAddress,
        content = "hello",
        timestamp = "2023-11-14T22:13:20Z",
        blockTime = 1_700_000_000_000L,
        isOutgoing = isOutgoing,
        messageType = messageType,
        deliveryStatus = deliveryStatus
    )

    @Test
    fun `imported messages are always marked read, since the archive has no per-message read state`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(isOutgoing = false), contactAddress, myAddress)
        assertTrue(entity.isRead)
    }

    @Test
    fun `outgoing archive message becomes a sent-direction entity`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(isOutgoing = true), contactAddress, myAddress)
        assertEquals("sent", entity.direction)
    }

    @Test
    fun `incoming archive message becomes a received-direction entity`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(isOutgoing = false), contactAddress, myAddress)
        assertEquals("received", entity.direction)
    }

    @Test
    fun `an iOS warning delivery status is preserved as-is`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(deliveryStatus = "warning"), contactAddress, myAddress)
        assertEquals("warning", entity.deliveryStatus)
    }

    @Test
    fun `an unrecognized delivery status falls back to sent rather than crashing`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(deliveryStatus = "bogus"), contactAddress, myAddress)
        assertEquals("sent", entity.deliveryStatus)
    }

    @Test
    fun `entity always attaches to the currently active wallet address, not any address in the archive`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(), contactAddress, myAddress)
        assertEquals(myAddress, entity.walletAddress)
    }
}
