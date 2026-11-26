package com.kachat.app.util

import com.kachat.app.services.kachatnames.KachatNames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KSPT 0x06 (KasSigner v1.1) and v1.1 reply frames, as the iPhone checks them (961fc05a,
 * AUDIT_2026-11-25 Q1): against KasSigner v1.1's own SDK vectors (`spec/vectors/v110/sdk`), so the
 * requests K writes are byte-identical to what KasSigner's writer makes for the same transaction,
 * and the unit's reply to a quantum sweep decodes and reassembles from its v1.1 frames.
 */
class KsptV6AndFramesTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private val zeroSubnetwork = "00".repeat(20)

    // `sweep.request`: a 22' sweep, two coins of the KCC-3 receive 0 address, coin 1 hinted, to
    // abandon 12's receive 0.
    private val sweepRequest =
        "4b53505406000000020100000000000000000000000000000000000000000000000000000000000000000000000000000404040404040404040404040404040404040404040404040404040404040404040000000065cd1d00000000000000000000000001000023aa201ea68b16641e8d5eff2c0b8a9ea963dd069630fadd859604da7ada91cfd29d428700000005050505050505050505050505050505050505050505050505050505050505050500000080f0fa0200000000000000000000000001000023aa201ea68b16641e8d5eff2c0b8a9ea963dd069630fadd859604da7ada91cfd29d4287000000100bc42000000000000022201bacea84ca721c95d67ecace19bc499a77c03726bc8739af637bcd89abaaf058ac46010000000000000000"

    // `sweep.reply.abandon12`: the unit's reply, with its redeem scripts.
    private val sweepReply =
        "4b53505406000000020100000000000000000000000000000000000000000000000000000000000000000000000000000404040404040404040404040404040404040404040404040404040404040404040000000065cd1d00000000000000000000000001000023aa201ea68b16641e8d5eff2c0b8a9ea963dd069630fadd859604da7ada91cfd29d428701000186f00c3eb544a0f6cb4d6ef0963261f8bd095fc3c86ca67942e32f236df2021689f0f3ef905ae5aa4cbe4b3f41a067a6073b4a8d97214f6d4645c0e22cb9f4c5220020fa507ef4400da1016770879248322820e16cdea980b83261b5bb5d384f2e2b45ac05050505050505050505050505050505050505050505050505050505050505050500000080f0fa0200000000000000000000000001000023aa201ea68b16641e8d5eff2c0b8a9ea963dd069630fadd859604da7ada91cfd29d4287010001bda457f6a2f113b85aeb2b81c74f44dc23c920b676dd53a830a0d4d3eaaba9ae6390f61a6cdfa1ebd754b06a1bc7aecd0187cc64deef99a737c4f0d0d8497fbc220020fa507ef4400da1016770879248322820e16cdea980b83261b5bb5d384f2e2b45ac100bc42000000000000022201bacea84ca721c95d67ecace19bc499a77c03726bc8739af637bcd89abaaf058ac46010000000000000000"

    // `single.request`: a 44' spend, two coins (receive 0, change 3), 4 KAS to zoo 12's receive 0,
    // change to change 4 (inside the unit's bank, so no hints).
    private val singleRequest =
        "4b535054060000000202000000000000000000000000000000000000000000000000000000000000000000000000000001010101010101010101010101010101010101010101010101010101010101010100000000a3e11100000000000000000000000001000022201bacea84ca721c95d67ecace19bc499a77c03726bc8739af637bcd89abaaf058ac00000002020202020202020202020202020202020202020202020202020202020202020200000000c2eb0b00000000000000000000000001000022206259d1386d5e3c2c318dac760a179bb988eca841879d24791022ce805c725f8fac0000000084d71700000000000022204f2e4efeec55d8faaeb5fc41f70597fc68c135bfe81744ccfb2c126f8b5c5bc8ac3003ef0500000000000022200dfc8c6f84d339150652b66d1a5900bd086663875240ea714290c523bf346113ac"

    private val kcc3Lock = "aa201ea68b16641e8d5eff2c0b8a9ea963dd069630fadd859604da7ada91cfd29d4287"
    private val kcc3Redeem = "20fa507ef4400da1016770879248322820e16cdea980b83261b5bb5d384f2e2b45ac"
    private val abandonReceive0 = "201bacea84ca721c95d67ecace19bc499a77c03726bc8739af637bcd89abaaf058ac"

    private fun input(txByte: String, index: Int, amount: Long, spk: String) = KsptCodec.UnsignedInput(
        prevTxId = txByte.repeat(32), prevIndex = index, amountSompi = amount,
        sequence = 0, sigOpCount = 1, spkVersion = 0, spkScriptHex = spk
    )

    private fun sweepInputs() = listOf(
        input("04", 4, 500_000_000L, kcc3Lock),
        input("05", 5, 50_000_000L, kcc3Lock),
    )

    @Test
    fun `the sweep request is byte-identical to KasSigner's`() {
        val bytes = KsptCodec.encodeUnsignedV6(
            txVersion = 0, lockTime = 0, subnetworkIdHex = zeroSubnetwork, gas = 0, payloadHex = null,
            inputs = sweepInputs(),
            inputHints = listOf(null, KsptCodec.InputHint.Kcc3(chain = 0, addrIndex = 0)),
            outputs = listOf(KsptCodec.UnsignedOutput(549_718_800L, 0, abandonReceive0)),
        )
        assertEquals(sweepRequest, bytes.hex())
    }

    @Test
    fun `the single request is byte-identical to KasSigner's`() {
        val bytes = KsptCodec.encodeUnsignedV6(
            txVersion = 0, lockTime = 0, subnetworkIdHex = zeroSubnetwork, gas = 0, payloadHex = "",
            inputs = listOf(
                input("01", 1, 300_000_000L, abandonReceive0),
                input("02", 2, 200_000_000L, "206259d1386d5e3c2c318dac760a179bb988eca841879d24791022ce805c725f8fac"),
            ),
            outputs = listOf(
                KsptCodec.UnsignedOutput(400_000_000L, 0, "204f2e4efeec55d8faaeb5fc41f70597fc68c135bfe81744ccfb2c126f8b5c5bc8ac"),
                KsptCodec.UnsignedOutput(99_550_000L, 0, "200dfc8c6f84d339150652b66d1a5900bd086663875240ea714290c523bf346113ac"),
            ),
        )
        assertEquals(singleRequest, bytes.hex())
    }

    @Test
    fun `the requests read back`() {
        val sweep = KsptCodec.decode(hex(sweepRequest)).getOrThrow()
        assertEquals(KsptCodec.VERSION_V6, sweep.version)
        assertFalse(sweep.signed)
        assertEquals(2, sweep.inputs.size)
        assertNull(sweep.inputs[0].hint)
        assertEquals(KsptCodec.InputHint.Kcc3(0, 0), sweep.inputs[1].hint)
        assertEquals(listOf(KsptCodec.Record.Kcc3InputHint(1, 0, 0)), sweep.records)
        assertNull(sweep.inputs[0].redeemScriptHex)
        assertEquals(549_718_800L, sweep.outputs[0].valueSompi)

        val single = KsptCodec.decode(hex(singleRequest)).getOrThrow()
        assertEquals(2, single.outputs.size)
        assertTrue(single.records.isEmpty())
    }

    @Test
    fun `the unit's sweep reply decodes with its signatures and redeem scripts`() {
        val reply = KsptCodec.decode(hex(sweepReply)).getOrThrow()
        assertEquals(KsptCodec.VERSION_V6, reply.version)
        assertTrue(reply.signed)
        reply.inputs.forEach { input ->
            assertEquals(1, input.signatures.size)
            assertEquals(0, input.signatures[0].pubkeyPos)
            assertEquals(1, input.sighashType)
            assertEquals(128, input.signatureHex!!.length)
            assertEquals(kcc3Redeem, input.redeemScriptHex)
            // The redeem is the script the input's address is the hash of (what broadcastSigned checks).
            assertEquals(input.spkScriptHex, KachatNames.Codec.p2shScript(hex(input.redeemScriptHex!!)).hex())
        }
        assertTrue(reply.records.none { it is KsptCodec.Record.Covenant || it is KsptCodec.Record.CoinCovenant })
    }

    @Test
    fun `the 0x06 parser refuses what KasSigner refuses`() {
        val good = hex(sweepRequest)
        // reserved flags
        assertTrue(KsptCodec.decode(good.copyOf().also { it[5] = 1 }).isFailure)
        // a trailing unknown record
        assertTrue(KsptCodec.decode(good + byteArrayOf(0x50)).isFailure)
        // a repeated hint for input 1
        assertTrue(KsptCodec.decode(good + hex("46010000000000000000")).isFailure)
        // a hint naming input 2, which doesn't exist
        val outOfRange = good.copyOf().also { it[it.size - 9] = 2 }
        assertTrue(KsptCodec.decode(outOfRange).isFailure)
        // truncated
        assertTrue(KsptCodec.decode(good.copyOf(good.size - 3)).isFailure)
        // another version
        assertTrue(KsptCodec.decode(good.copyOf().also { it[4] = 2 }).isFailure)
    }

    @Test
    fun `a hint must fit its input's lock`() {
        val result = runCatching {
            KsptCodec.encodeUnsignedV6(
                txVersion = 0, lockTime = 0, subnetworkIdHex = zeroSubnetwork, gas = 0, payloadHex = null,
                inputs = listOf(input("01", 0, 1_000L, abandonReceive0)),
                inputHints = listOf(KsptCodec.InputHint.Kcc3(0, 0)),
                outputs = listOf(KsptCodec.UnsignedOutput(500L, 0, abandonReceive0)),
            )
        }
        assertTrue(result.isFailure)
        // a hardened index or a third chain is refused too
        val hardened = runCatching {
            KsptCodec.encodeUnsignedV6(
                txVersion = 0, lockTime = 0, subnetworkIdHex = zeroSubnetwork, gas = 0, payloadHex = null,
                inputs = sweepInputs(),
                inputHints = listOf(null, KsptCodec.InputHint.Kcc3(0, 0x8000_0000L)),
                outputs = listOf(KsptCodec.UnsignedOutput(549_718_800L, 0, abandonReceive0)),
            )
        }
        assertTrue(hardened.isFailure)
    }

    @Test
    fun `the 0x01 path is unchanged`() {
        val bytes = KsptCodec.encodeUnsigned(
            txVersion = 0, lockTime = 0, subnetworkIdHex = zeroSubnetwork, gas = 0, payloadHex = null,
            inputs = listOf(input("01", 1, 300_000_000L, abandonReceive0)),
            outputs = listOf(KsptCodec.UnsignedOutput(200_000_000L, 0, abandonReceive0)),
        )
        assertEquals(0x01, bytes[4].toInt())
        val decoded = KsptCodec.decode(bytes).getOrThrow()
        assertEquals(0x01, decoded.version)
        assertFalse(decoded.signed)
        assertTrue(decoded.records.isEmpty())
    }

    // ---- v1.1 frames (Q1)

    @Test
    fun `frame 0 carries the payload's SHA-256 digest`() {
        val reply = hex(sweepReply)
        assertEquals("6a4329d5", QrFrameChunker.V11.digest(reply).hex())
        val frames = QrFrameChunker.V11.frames(reply)
        assertEquals(3, frames.size) // 491 + 3 bytes over 225 a frame
        assertEquals("1b010003" + "6a4329d5", frames[0].copyOf(8).hex())
        assertEquals("1b0101036a", frames[1].copyOf(5).hex())
        // write_split's even split: every fragment the same size but the last
        assertEquals(165 - 3, frames[0].size - 8)
        assertEquals(165, frames[1].size - 5)
    }

    private fun accumulator() = QrFrameChunker.Accumulator({ KsptCodec.looksLikeKspt(it) })

    @Test
    fun `the unit's v1_1 frames reassemble in any order, with duplicates and noise`() {
        val reply = hex(sweepReply)
        val frames = QrFrameChunker.V11.frames(reply, perFrame = 120)
        assertTrue(frames.size > 2)

        val inOrder = accumulator()
        var out: ByteArray? = null
        frames.forEach { out = inOrder.addFrame(it) ?: out }
        assertArrayEquals(reply, out)

        val shuffled = accumulator()
        val noise = byteArrayOf(0x1B, 0x03, 0x00, 0x02, 0x11, 0x22, 0x33, 0x44) // a kpub frame
        var result: ByteArray? = null
        for (frame in frames.reversed() + noise + frames[1]) {
            result = shuffled.addFrame(frame) ?: result
        }
        assertArrayEquals(reply, result)
        val decoded = KsptCodec.decode(result!!).getOrThrow()
        assertTrue(decoded.signed)
    }

    @Test
    fun `a single framed code is read too`() {
        val reply = hex(sweepReply)
        val single = QrFrameChunker.V11.frames(reply, perFrame = 1000)
        assertEquals(1, single.size)
        assertArrayEquals(reply, accumulator().addFrame(single[0]))
    }

    @Test
    fun `a forged digest or a mixed transmission never completes`() {
        val reply = hex(sweepReply)
        val frames = QrFrameChunker.V11.frames(reply, perFrame = 200)
        val forged = frames[0].copyOf().also { it[5] = (it[5] + 1).toByte() }
        val acc = accumulator()
        var out: ByteArray? = null
        (listOf(forged) + frames.drop(1)).forEach { out = acc.addFrame(it) ?: out }
        assertNull(out)

        // a frame of another transmission starts the set over: never joined with this one's
        val other = hex(sweepRequest) + ByteArray(200)
        val otherFrames = QrFrameChunker.V11.frames(other, perFrame = 200)
        assertEquals(frames.size, otherFrames.size)
        val mixed = accumulator()
        var mixedOut: ByteArray? = null
        listOf(frames[0], otherFrames[1], frames[1], frames[2]).forEach { mixedOut = mixed.addFrame(it) ?: mixedOut }
        assertNull(mixedOut)
        assertArrayEquals(reply, mixed.addFrame(frames[0]))
    }

    @Test
    fun `v1_0_7 frames and bare payloads still read`() {
        val payload = hex(sweepReply)
        val frames = QrFrameChunker.chunk(payload)
        assertTrue(frames.size > 1)
        val acc = accumulator()
        var out: ByteArray? = null
        frames.shuffled(java.util.Random(7)).forEach { out = acc.addFrame(it) ?: out }
        assertArrayEquals(payload, out)

        val bare = hex(singleRequest).copyOf(120).also { KsptCodec.MAGIC.copyInto(it) }
        assertNotNull(accumulator().addFrame(bare))
        // a frame of another v1.1 format is ignored, not read
        assertNull(accumulator().addFrame(QrFrameChunker.V11.frames(payload, format = 3, perFrame = 1000)[0]))
    }
}
