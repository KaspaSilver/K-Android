package com.kachat.app.util

import com.kachat.app.services.RawTransaction
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * KSPT ("Kaspa Signable Partial Transaction") — the compact binary transport format KasSigner's
 * firmware scans/displays for air-gapped signing. Field order and widths below must match
 * KasSigner exactly, or the device either rejects the QR outright or (worse) silently misparses it.
 * Same wire format as the iPhone's `KsptCodec.swift`.
 *
 * Two versions are spoken here:
 *
 * **0x01** ([encodeUnsigned], read by [decode]): plain single-sig P2PK, what units on v1.0.x read.
 * KasSigner v1.1 still reads it and answers a 0x01 request in the same 0x01 shape. Unchanged.
 * ```
 * Header:  magic(4)="KSPT"  version(1)=0x01  flags(1) [bit0: signed, bit1: has redeem script]
 * Global:  txVersion(2)  numInputs(1)  numOutputs(1)  lockTime(8)  subnetworkId(20)  gas(8)
 *          payloadLen(2)  payload(payloadLen)
 * Input:   prevTxId(32)  prevIndex(4)  amount(8)  sequence(8)  sigOpCount(1)
 *          spkVersion(2)  spkLen(1)  spkScript(spkLen)
 *          [if signed: sigLen(1)  signature(sigLen)  sighashType(1)]
 * Output:  value(8)  spkVersion(2)  spkLen(1)  spkScript(spkLen)
 * ```
 *
 * **0x06** ([encodeUnsignedV6], read by [decode]): KasSigner v1.1's canonical version
 * (`crates/kassigner-protocol/src/kspt/mod.rs` and `writer.rs`), which carries KCC-3
 * quantum-resistant inputs. `flags` is reserved (zero); every input has a signature count and a
 * redeem length, zero when unsigned; hints and covenants are records after the outputs.
 * ```
 * Header:  magic(4)="KSPT"  version(1)=0x06  flags(1)=0x00
 * Global:  as 0x01
 * Input:   prevTxId(32)  prevIndex(4)  amount(8)  sequence(8)  sigOpCount(1)
 *          spkVersion(2)  spkLen(1 | 0xFF u16)  spkScript
 *          sigCount(1)  sigCount × [pubkeyPos(1) sighashType(1) signature(64)]  (ascending pos)
 *          redeemLen(u16)  redeem(redeemLen)
 * Output:  value(8)  spkVersion(2)  spkLen(1 | 0xFF u16)  spkScript
 * Records: 0x43 covenant(out u8, authInput u16, id 32)   0x44 / 0x45 hint(idx, cosigner, chain, addr u32)
 *          0x46 KCC-3 input hint(idx, chain, addr u32)  0x47 / 0x48 account hint(idx, chain, addr u32)
 *          0x49 coin covenant(in u8, id 32)
 * ```
 * A script length is one byte below 255, else 0xFF and a u16 of at least 255 (the short form is
 * required). One hint of any kind per input and per output, one covenant per output and per coin.
 *
 * A KCC-3 (`m/22'`) address is P2SH of `0x20 <32-byte x-only key> 0xAC`. K never knows that key:
 * the request carries an empty redeem script and a 0x46 hint, and the unit writes the redeem into
 * its reply.
 */
object KsptCodec {
    val MAGIC = byteArrayOf('K'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 'T'.code.toByte())
    private const val VERSION: Int = 0x01
    private const val FLAG_SIGNED = 0x01
    private const val FLAG_HAS_REDEEM_SCRIPT = 0x02

    /** The canonical version KasSigner v1.1 writes and reads. */
    const val VERSION_V6: Int = 0x06

    // Matches firmware MAX_INPUTS=32 as of KasSigner v1.0.5 (was 8; devices on older
    // firmware reject >8-input — and pre-1.0.5, even >5-input — transactions).
    const val MAX_INPUTS = 32
    const val MAX_OUTPUTS = 4

    // KasSigner v1.1's `Profile::ESP32S3` (crates/kassigner-protocol/src/profile.rs), which every
    // board (M5, Waveshare, P4) uses unchanged apart from frame counts (`Capacity::derive`), and
    // firmware/kassigner-fw/src/limits.rs (MAX_INPUTS 32, MAX_OUTPUTS 8). Only for 0x06: a unit on
    // v1.0.x reads 0x01 within MAX_INPUTS / MAX_OUTPUTS above.
    const val MAX_INPUTS_V6 = 32
    const val MAX_OUTPUTS_V6 = 8
    const val MAX_SCRIPT_V6 = 512
    const val MAX_REDEEM_V6 = 1024
    const val MAX_PAYLOAD_V6 = 768
    const val MAX_SIGS_PER_INPUT_V6 = 5

    /** Total supply in sompi: a value, or a running total, above it is refused (KasSigner's
     *  `checked_add_value`, upstream's `check_transaction_output_value_ranges`). */
    const val MAX_SOMPI: Long = 29_000_000_000L * 100_000_000L

    /** The length of KCC-3's redeem script, `0x20 <P> 0xAC`. */
    const val KCC3_REDEEM_LENGTH = 34

    private const val LEN_ESCAPE = 0xFF
    private const val SIGNATURE_LENGTH = 64
    private const val REC_COVENANT = 0x43
    private const val REC_HINT_IN = 0x44
    private const val REC_HINT_OUT = 0x45
    private const val REC_KCC3_HINT_IN = 0x46
    private const val REC_ACCOUNT_HINT_IN = 0x47
    private const val REC_ACCOUNT_HINT_OUT = 0x48
    private const val REC_COIN_COVENANT = 0x49

    /** Starts with the 4-byte "KSPT" magic — the same check KasSigner's own frame-0 detector uses. */
    fun looksLikeKspt(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)

    data class UnsignedInput(
        val prevTxId: String, // 64 hex chars
        val prevIndex: Int,
        val amountSompi: Long,
        val sequence: Long,
        val sigOpCount: Int,
        val spkVersion: Int,
        val spkScriptHex: String
    )

    data class UnsignedOutput(
        val valueSompi: Long,
        val spkVersion: Int,
        val spkScriptHex: String
    )

    /** Where an input's key is, for a 0x06 request. One per input at most. Chains and indices are
     *  u32 on the wire; held as Long so the whole range fits. */
    sealed class InputHint {
        /** Record 0x46: a KCC-3 address, [chain]/[addrIndex] below `m/22'/111111'/0'`. The input's
         *  lock must be P2SH; its redeem goes empty and the unit supplies it. */
        data class Kcc3(val chain: Long, val addrIndex: Long) : InputHint()

        /** Record 0x47: a single-key (`m/44'/111111'/0'`) key at [chain]/[addrIndex]. KasSigner's
         *  SDK writes it only past the unit's bank (receive index >= 20, change index >= 5); inside
         *  it the unit finds the key by itself, so it is optional there. */
        data class Account(val chain: Long, val addrIndex: Long) : InputHint()
    }

    /** A KSPT payload this codec refuses, with KasSigner's reason. */
    class KsptException(message: String) : IllegalArgumentException(message)

    /**
     * Builds the unsigned KSPT bytes for the given transaction shape. Doesn't take a
     * [RawTransaction] directly — [RawTransaction.inputs] alone has no UTXO amount or
     * scriptPublicKey (those live on the selected [com.kachat.app.services.UtxoEntry] list, not
     * the tx itself), so callers assemble [UnsignedInput]/[UnsignedOutput] from their selection
     * result instead of this function re-deriving it.
     */
    fun encodeUnsigned(
        txVersion: Int,
        lockTime: Long,
        subnetworkIdHex: String,
        gas: Long,
        payloadHex: String?,
        inputs: List<UnsignedInput>,
        outputs: List<UnsignedOutput>
    ): ByteArray {
        require(inputs.size in 1..MAX_INPUTS) { "KSPT supports 1-$MAX_INPUTS inputs ($MAX_INPUTS_V6 on KasSigner v1.1), got ${inputs.size}" }
        require(outputs.size in 1..MAX_OUTPUTS) { "KSPT supports 1-$MAX_OUTPUTS outputs ($MAX_OUTPUTS_V6 on KasSigner v1.1), got ${outputs.size}" }

        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        out.write(0x00) // unsigned, no redeem script

        val payloadBytes = payloadHex?.hexToBytes() ?: ByteArray(0)
        require(payloadBytes.size <= 128) { "KSPT payload is too large (${payloadBytes.size} bytes)" }

        out.writeU16Le(txVersion)
        out.write(inputs.size)
        out.write(outputs.size)
        out.writeU64Le(lockTime)
        val subnetworkBytes = subnetworkIdHex.hexToBytes()
        require(subnetworkBytes.size == 20) { "subnetworkId must be 20 bytes, got ${subnetworkBytes.size}" }
        out.write(subnetworkBytes)
        out.writeU64Le(gas)
        out.writeU16Le(payloadBytes.size)
        out.write(payloadBytes)

        inputs.forEach { input ->
            val txIdBytes = input.prevTxId.hexToBytes()
            require(txIdBytes.size == 32) { "prevTxId must be 32 bytes" }
            out.write(txIdBytes)
            out.writeU32Le(input.prevIndex.toLong())
            out.writeU64Le(input.amountSompi)
            out.writeU64Le(input.sequence)
            out.write(input.sigOpCount)
            out.writeU16Le(input.spkVersion)
            val spkBytes = input.spkScriptHex.hexToBytes()
            require(spkBytes.size <= 64) { "KSPT script is too large (${spkBytes.size} bytes)" }
            out.write(spkBytes.size)
            out.write(spkBytes)
        }

        outputs.forEach { output ->
            out.writeU64Le(output.valueSompi)
            out.writeU16Le(output.spkVersion)
            val spkBytes = output.spkScriptHex.hexToBytes()
            require(spkBytes.size <= 64) { "KSPT script is too large (${spkBytes.size} bytes)" }
            out.write(spkBytes.size)
            out.write(spkBytes)
        }

        return out.toByteArray()
    }

    /**
     * Builds an unsigned **0x06** request, as KasSigner's `write_draft` does for the same
     * transaction: every input with no signatures and an empty redeem script, then the records,
     * all 0x46 (KCC-3) hints in input order, then all 0x47 (account) hints in input order.
     *
     * [inputHints] is empty (no hints) or one entry per input. A [InputHint.Kcc3] input's lock must
     * be P2SH (`aa20 <32> 87`); an [InputHint.Account] input's lock must be P2PK. A hint's chain is
     * 0 (receive) or 1 (change) and its index below 2^31, as KasSigner's SDK refuses otherwise.
     * Limits are v1.1's profile ([MAX_INPUTS_V6] ...), and a value or the running total of inputs
     * or outputs above the total supply is refused, as the unit would.
     */
    fun encodeUnsignedV6(
        txVersion: Int,
        lockTime: Long,
        subnetworkIdHex: String,
        gas: Long,
        payloadHex: String?,
        inputs: List<UnsignedInput>,
        inputHints: List<InputHint?> = emptyList(),
        outputs: List<UnsignedOutput>
    ): ByteArray {
        if (inputs.size !in 1..MAX_INPUTS_V6) throw KsptException("KSPT supports 1-$MAX_INPUTS inputs ($MAX_INPUTS_V6 on KasSigner v1.1), got ${inputs.size}")
        if (outputs.size !in 1..MAX_OUTPUTS_V6) throw KsptException("KSPT supports 1-$MAX_OUTPUTS outputs ($MAX_OUTPUTS_V6 on KasSigner v1.1), got ${outputs.size}")
        if (inputHints.isNotEmpty() && inputHints.size != inputs.size) {
            throw KsptException("KSPT has ${inputs.size} inputs but ${inputHints.size} hints")
        }
        val hints: List<InputHint?> = if (inputHints.isEmpty()) List(inputs.size) { null } else inputHints

        val payloadBytes = if (payloadHex.isNullOrEmpty()) ByteArray(0) else payloadHex.hexToBytes()
        if (payloadBytes.size > MAX_PAYLOAD_V6) throw KsptException("KSPT payload is too large (${payloadBytes.size} bytes)")
        val subnetworkBytes = subnetworkIdHex.hexToBytes()
        if (subnetworkBytes.size != 20) throw KsptException("subnetworkId must be 20 bytes, got ${subnetworkBytes.size}")

        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION_V6)
        out.write(0x00) // flags: reserved in 0x06
        out.writeU16Le(txVersion)
        out.write(inputs.size)
        out.write(outputs.size)
        out.writeU64Le(lockTime)
        out.write(subnetworkBytes)
        out.writeU64Le(gas)
        out.writeU16Le(payloadBytes.size)
        out.write(payloadBytes)

        var totalIn = 0L
        inputs.forEachIndexed { i, input ->
            val txIdBytes = input.prevTxId.hexToBytes()
            if (txIdBytes.size != 32) throw KsptException("prevTxId must be 32 bytes, got ${txIdBytes.size}")
            val spkBytes = input.spkScriptHex.hexToBytes()
            totalIn = addValue(totalIn, input.amountSompi)
            when (val hint = hints[i]) {
                is InputHint.Kcc3 ->
                    if (!isHintLevel(hint.chain, hint.addrIndex) || !isPayToScriptHash(spkBytes)) throw badHint(i)
                is InputHint.Account ->
                    if (!isHintLevel(hint.chain, hint.addrIndex) || !isPayToPubKey(spkBytes)) throw badHint(i)
                null -> Unit
            }
            out.write(txIdBytes)
            out.writeU32Le(input.prevIndex.toLong() and 0xFFFF_FFFFL)
            out.writeU64Le(input.amountSompi)
            out.writeU64Le(input.sequence)
            out.write(input.sigOpCount)
            appendScript(out, input.spkVersion, spkBytes)
            out.write(0x00)      // no signatures
            out.writeU16Le(0)    // no redeem script: the unit supplies KCC-3's
        }

        var totalOut = 0L
        outputs.forEach { output ->
            val spkBytes = output.spkScriptHex.hexToBytes()
            totalOut = addValue(totalOut, output.valueSompi)
            out.writeU64Le(output.valueSompi)
            appendScript(out, output.spkVersion, spkBytes)
        }

        // The writer's fixed record order: covenants, coin covenants, 0x44, 0x46, 0x47, 0x45, 0x48.
        // K writes no covenants and no group or output hints, so 0x46s then 0x47s.
        hints.forEachIndexed { i, hint ->
            if (hint is InputHint.Kcc3) {
                out.write(REC_KCC3_HINT_IN)
                out.write(i)
                out.writeU32Le(hint.chain)
                out.writeU32Le(hint.addrIndex)
            }
        }
        hints.forEachIndexed { i, hint ->
            if (hint is InputHint.Account) {
                out.write(REC_ACCOUNT_HINT_IN)
                out.write(i)
                out.writeU32Le(hint.chain)
                out.writeU32Le(hint.addrIndex)
            }
        }
        return out.toByteArray()
    }

    private fun badHint(input: Int) = KsptException("KSPT input $input's hint doesn't fit its address")

    data class DecodedSignature(
        val pubkeyPos: Int,
        val sighashType: Int,
        val signatureHex: String
    )

    data class DecodedInput(
        val prevTxId: String,
        val prevIndex: Int,
        val amountSompi: Long,
        val sequence: Long,
        val sigOpCount: Int,
        val spkVersion: Int,
        val spkScriptHex: String,
        // Present only when the payload is a signed response and this input was actually signed —
        // a still-unsigned input in a partially-signed response round-trips with
        // signatureHex=null. On 0x06 it is the signature record at key position 0.
        val signatureHex: String?,
        val sighashType: Int?,
        /** The redeem script the payload carries for this input (0x06), null when empty. On a
         *  KCC-3 reply it is the unit's `0x20 <P> 0xAC`. The codec checks its shape, not that its
         *  hash is the input's P2SH lock: the caller must (blake2b-256 of it == the lock's 32 bytes). */
        val redeemScriptHex: String? = null,
        /** Every signature record on the input, ascending by key position (0x01: the one). */
        val signatures: List<DecodedSignature> = emptyList(),
        /** The input's 0x46 / 0x47 hint, if the payload carries one. */
        val hint: InputHint? = null,
    )

    data class DecodedOutput(
        val valueSompi: Long,
        val spkVersion: Int,
        val spkScriptHex: String
    )

    /** A 0x06 record after the outputs, as read. */
    sealed class Record {
        data class Covenant(val outputIndex: Int, val authorizingInput: Int, val covenantIdHex: String) : Record()     // 0x43
        data class InputHintRecord(val index: Int, val cosigner: Long, val chain: Long, val addrIndex: Long) : Record() // 0x44
        data class OutputHint(val index: Int, val cosigner: Long, val chain: Long, val addrIndex: Long) : Record()      // 0x45
        data class Kcc3InputHint(val index: Int, val chain: Long, val addrIndex: Long) : Record()                       // 0x46
        data class AccountInputHint(val index: Int, val chain: Long, val addrIndex: Long) : Record()                    // 0x47
        data class AccountOutputHint(val index: Int, val chain: Long, val addrIndex: Long) : Record()                   // 0x48
        data class CoinCovenant(val inputIndex: Int, val covenantIdHex: String) : Record()                              // 0x49
    }

    data class Decoded(
        /** 0x06: true when any input carries a signature; 0x01: the signed flag. */
        val signed: Boolean,
        val txVersion: Int,
        val lockTime: Long,
        val subnetworkIdHex: String,
        val gas: Long,
        val payloadHex: String,
        val inputs: List<DecodedInput>,
        val outputs: List<DecodedOutput>,
        /** The KSPT version read: 0x01 or 0x06. */
        val version: Int = VERSION,
        /** 0x06's records in the order they appeared (empty on 0x01). A caller that builds no
         *  covenants should refuse a reply carrying [Record.Covenant] / [Record.CoinCovenant]. */
        val records: List<Record> = emptyList(),
    )

    /** Reads a 0x01 payload (unchanged) or a 0x06 one; any other version fails. */
    fun decode(bytes: ByteArray): Result<Decoded> {
        return try {
            require(looksLikeKspt(bytes)) { "Not a KSPT payload (bad magic)" }
            require(bytes.size >= 5) { "KSPT payload is truncated" }
            if ((bytes[4].toInt() and 0xFF) == VERSION_V6) return Result.success(decodeV6(bytes))
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4) // skip magic
            val version = buf.get().toInt() and 0xFF
            require(version == VERSION) { "Unsupported KSPT version $version" }
            val flags = buf.get().toInt() and 0xFF
            val signed = flags and FLAG_SIGNED != 0
            require(flags and FLAG_HAS_REDEEM_SCRIPT == 0) { "Multisig/redeem-script KSPT isn't supported" }

            val txVersion = buf.readU16()
            val numInputs = buf.get().toInt() and 0xFF
            val numOutputs = buf.get().toInt() and 0xFF
            require(numInputs in 1..MAX_INPUTS) { "Invalid input count $numInputs" }
            require(numOutputs in 1..MAX_OUTPUTS) { "Invalid output count $numOutputs" }
            val lockTime = buf.long
            val subnetworkBytes = ByteArray(20).also { buf.get(it) }
            val gas = buf.long
            val payloadLen = buf.readU16()
            require(payloadLen <= 128) { "Invalid payload length $payloadLen" }
            val payloadBytes = ByteArray(payloadLen).also { buf.get(it) }

            val inputs = (0 until numInputs).map {
                val txIdBytes = ByteArray(32).also { arr -> buf.get(arr) }
                val prevIndex = buf.int
                val amount = buf.long
                val sequence = buf.long
                val sigOpCount = buf.get().toInt() and 0xFF
                val spkVersion = buf.readU16()
                val spkLen = buf.get().toInt() and 0xFF
                require(spkLen <= 64) { "Invalid input scriptPublicKey length $spkLen" }
                val spkBytes = ByteArray(spkLen).also { arr -> buf.get(arr) }
                var sigHex: String? = null
                var sighashType: Int? = null
                if (signed) {
                    val sigLen = buf.get().toInt() and 0xFF
                    require(sigLen <= 64) { "Invalid signature length $sigLen" }
                    if (sigLen > 0) {
                        val sigBytes = ByteArray(sigLen).also { arr -> buf.get(arr) }
                        sigHex = sigBytes.toHexString()
                        sighashType = buf.get().toInt() and 0xFF
                    }
                }
                DecodedInput(
                    prevTxId = txIdBytes.toHexString(),
                    prevIndex = prevIndex,
                    amountSompi = amount,
                    sequence = sequence,
                    sigOpCount = sigOpCount,
                    spkVersion = spkVersion,
                    spkScriptHex = spkBytes.toHexString(),
                    signatureHex = sigHex,
                    sighashType = sighashType,
                    redeemScriptHex = null,
                    signatures = sigHex?.let { listOf(DecodedSignature(0, sighashType ?: 0, it)) } ?: emptyList(),
                    hint = null,
                )
            }

            val outputs = (0 until numOutputs).map {
                val value = buf.long
                val spkVersion = buf.readU16()
                val spkLen = buf.get().toInt() and 0xFF
                require(spkLen <= 64) { "Invalid output scriptPublicKey length $spkLen" }
                val spkBytes = ByteArray(spkLen).also { arr -> buf.get(arr) }
                DecodedOutput(valueSompi = value, spkVersion = spkVersion, spkScriptHex = spkBytes.toHexString())
            }

            Result.success(
                Decoded(
                    signed = signed,
                    txVersion = txVersion,
                    lockTime = lockTime,
                    subnetworkIdHex = subnetworkBytes.toHexString(),
                    gas = gas,
                    payloadHex = payloadBytes.toHexString(),
                    inputs = inputs,
                    outputs = outputs,
                    version = VERSION,
                    records = emptyList(),
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- 0x06

    private class RawInputV6(
        val txId: ByteArray, val prevIndex: Int, val amount: Long, val sequence: Long, val sigOpCount: Int,
        val spkVersion: Int, val spk: ByteArray, val signatures: List<DecodedSignature>, val redeem: ByteArray
    )

    /**
     * Reads a canonical 0x06 payload as KasSigner's `parse` does (mod.rs), refusing what it
     * refuses: reserved flags, zero inputs or outputs, counts and lengths past the profile, a
     * non-canonical script length, amounts past the supply, a repeated or descending signature
     * position, unknown records, repeated records, records naming an index the payload lacks, a
     * covenant naming an input it lacks, and trailing bytes. Then, for safety, more than one
     * signature (or one not at position 0) on a single-key input (a P2PK lock, a KCC-3 redeem,
     * or a 0x46 hint), a redeem script on an input whose lock isn't P2SH, and a redeem on a
     * KCC-3-hinted input that isn't `0x20 <32> 0xAC`.
     */
    private fun decodeV6(bytes: ByteArray): Decoded {
        val r = Reader(bytes)
        r.skip(4) // magic
        r.u8() // 0x06
        val flags = r.u8()
        if (flags != 0) throw KsptException("KSPT 0x06 flags must be zero, got $flags")

        val txVersion = r.u16()
        val numInputs = r.u8()
        val numOutputs = r.u8()
        val lockTime = r.u64()
        val subnetworkBytes = r.bytes(20)
        val gas = r.u64()
        val payloadLen = r.u16()
        if (numInputs !in 1..MAX_INPUTS_V6) throw KsptException("KSPT supports 1-$MAX_INPUTS inputs ($MAX_INPUTS_V6 on KasSigner v1.1), got $numInputs")
        if (numOutputs !in 1..MAX_OUTPUTS_V6) throw KsptException("KSPT supports 1-$MAX_OUTPUTS outputs ($MAX_OUTPUTS_V6 on KasSigner v1.1), got $numOutputs")
        if (payloadLen > MAX_PAYLOAD_V6) throw KsptException("KSPT payload is too large ($payloadLen bytes)")
        val payloadBytes = r.bytes(payloadLen)

        val raw = ArrayList<RawInputV6>(numInputs)
        var totalIn = 0L
        for (i in 0 until numInputs) {
            val txId = r.bytes(32)
            val prevIndex = r.u32().toInt()
            val amount = r.u64()
            totalIn = addValue(totalIn, amount)
            val sequence = r.u64()
            val sigOpCount = r.u8()
            val spkVersion = r.u16()
            val spkLen = r.scriptLen()
            if (spkLen > MAX_SCRIPT_V6) throw KsptException("KSPT script is too large ($spkLen bytes)")
            val spk = r.bytes(spkLen)

            val sigCount = r.u8()
            if (sigCount > MAX_SIGS_PER_INPUT_V6) throw KsptException("KSPT input $i carries $sigCount signatures, more than $MAX_SIGS_PER_INPUT_V6")
            val signatures = ArrayList<DecodedSignature>(sigCount)
            val seen = HashSet<Int>()
            repeat(sigCount) {
                val pos = r.u8()
                val sighash = r.u8()
                val sig = r.bytes(SIGNATURE_LENGTH)
                if (!seen.add(pos)) throw KsptException("KSPT input $i has two signatures at key position $pos")
                val last = signatures.lastOrNull()
                if (last != null && pos < last.pubkeyPos) throw KsptException("KSPT input $i's signature at position $pos is out of order")
                signatures.add(DecodedSignature(pos, sighash, sig.toHexString()))
            }
            val redeemLen = r.u16()
            if (redeemLen > MAX_REDEEM_V6) throw KsptException("KSPT redeem script must be <=$MAX_REDEEM_V6 bytes, got $redeemLen")
            val redeem = r.bytes(redeemLen)
            raw.add(RawInputV6(txId, prevIndex, amount, sequence, sigOpCount, spkVersion, spk, signatures, redeem))
        }

        val outputs = ArrayList<DecodedOutput>(numOutputs)
        var totalOut = 0L
        repeat(numOutputs) {
            val value = r.u64()
            totalOut = addValue(totalOut, value)
            val spkVersion = r.u16()
            val spkLen = r.scriptLen()
            if (spkLen > MAX_SCRIPT_V6) throw KsptException("KSPT script is too large ($spkLen bytes)")
            val spk = r.bytes(spkLen)
            outputs.add(DecodedOutput(value, spkVersion, spk.toHexString()))
        }

        // Records, in any order; one hint of any kind per input and per output, one covenant per
        // output and per coin (mod.rs `validate_records`).
        val records = ArrayList<Record>()
        val hintedIn = HashSet<Int>()
        val hintedOut = HashSet<Int>()
        val bound = HashSet<Int>()
        val coins = HashSet<Int>()
        val hints = HashMap<Int, InputHint>()
        fun outOfRange(marker: Int, at: Int, index: Int) =
            KsptException(String.format(java.util.Locale.ROOT, "KSPT record 0x%02x at byte %d names index %d, which doesn't exist", marker, at, index))
        fun repeated(marker: Int, at: Int) =
            KsptException(String.format(java.util.Locale.ROOT, "KSPT repeats record 0x%02x at byte %d", marker, at))
        while (r.remaining > 0) {
            val at = r.position
            when (val marker = r.u8()) {
                REC_COVENANT -> {
                    val index = r.u8()
                    val input = r.u16()
                    val id = r.bytes(32)
                    if (index >= numOutputs) throw outOfRange(marker, at, index)
                    if (input >= numInputs) throw KsptException("KSPT binds output $index to input $input, which doesn't exist")
                    if (!bound.add(index)) throw repeated(marker, at)
                    records.add(Record.Covenant(index, input, id.toHexString()))
                }
                REC_HINT_IN, REC_HINT_OUT -> {
                    val index = r.u8()
                    val cosigner = r.u32()
                    val chain = r.u32()
                    val addrIndex = r.u32()
                    val isIn = marker == REC_HINT_IN
                    if (index >= (if (isIn) numInputs else numOutputs)) throw outOfRange(marker, at, index)
                    val fresh = if (isIn) hintedIn.add(index) else hintedOut.add(index)
                    if (!fresh) throw repeated(marker, at)
                    records.add(
                        if (isIn) Record.InputHintRecord(index, cosigner, chain, addrIndex)
                        else Record.OutputHint(index, cosigner, chain, addrIndex)
                    )
                }
                REC_KCC3_HINT_IN, REC_ACCOUNT_HINT_IN, REC_ACCOUNT_HINT_OUT -> {
                    val index = r.u8()
                    val chain = r.u32()
                    val addrIndex = r.u32()
                    val isOut = marker == REC_ACCOUNT_HINT_OUT
                    if (index >= (if (isOut) numOutputs else numInputs)) throw outOfRange(marker, at, index)
                    val fresh = if (isOut) hintedOut.add(index) else hintedIn.add(index)
                    if (!fresh) throw repeated(marker, at)
                    when (marker) {
                        REC_KCC3_HINT_IN -> {
                            records.add(Record.Kcc3InputHint(index, chain, addrIndex))
                            hints[index] = InputHint.Kcc3(chain, addrIndex)
                        }
                        REC_ACCOUNT_HINT_IN -> {
                            records.add(Record.AccountInputHint(index, chain, addrIndex))
                            hints[index] = InputHint.Account(chain, addrIndex)
                        }
                        else -> records.add(Record.AccountOutputHint(index, chain, addrIndex))
                    }
                }
                REC_COIN_COVENANT -> {
                    val index = r.u8()
                    val id = r.bytes(32)
                    if (index >= numInputs) throw outOfRange(marker, at, index)
                    if (!coins.add(index)) throw repeated(marker, at)
                    records.add(Record.CoinCovenant(index, id.toHexString()))
                }
                else -> throw KsptException(String.format(java.util.Locale.ROOT, "KSPT has an unknown record 0x%02x at byte %d", marker, at))
            }
        }
        // `remaining` reached zero, so nothing trails; kept explicit as the parser's last check.
        if (r.remaining != 0) throw KsptException("KSPT has trailing bytes from byte ${r.position}")

        val inputs = raw.mapIndexed { i, input ->
            val hint = hints[i]
            if (input.redeem.isNotEmpty() && !isPayToScriptHash(input.spk)) {
                throw KsptException("KSPT input $i carries a redeem script its lock doesn't take")
            }
            if (hint is InputHint.Kcc3 && input.redeem.isNotEmpty() && !isKcc3Redeem(input.redeem)) {
                throw KsptException("KSPT input $i carries a redeem script its lock doesn't take")
            }
            val singleKey = isPayToPubKey(input.spk) || isKcc3Redeem(input.redeem) || hint is InputHint.Kcc3
            if (singleKey && (input.signatures.size > 1 || input.signatures.any { it.pubkeyPos != 0 })) {
                throw KsptException("KSPT input $i has one key but more than one signature, or one at another position")
            }
            val first = input.signatures.firstOrNull { it.pubkeyPos == 0 }
            DecodedInput(
                prevTxId = input.txId.toHexString(),
                prevIndex = input.prevIndex,
                amountSompi = input.amount,
                sequence = input.sequence,
                sigOpCount = input.sigOpCount,
                spkVersion = input.spkVersion,
                spkScriptHex = input.spk.toHexString(),
                signatureHex = first?.signatureHex,
                sighashType = first?.sighashType,
                redeemScriptHex = if (input.redeem.isEmpty()) null else input.redeem.toHexString(),
                signatures = input.signatures,
                hint = hint,
            )
        }

        return Decoded(
            signed = inputs.any { it.signatures.isNotEmpty() },
            txVersion = txVersion,
            lockTime = lockTime,
            subnetworkIdHex = subnetworkBytes.toHexString(),
            gas = gas,
            payloadHex = payloadBytes.toHexString(),
            inputs = inputs,
            outputs = outputs,
            version = VERSION_V6,
            records = records,
        )
    }

    /** `0x20 <32-byte x-only key> 0xAC`: KCC-3's redeem script. */
    fun isKcc3Redeem(script: ByteArray): Boolean =
        script.size == KCC3_REDEEM_LENGTH && (script[0].toInt() and 0xFF) == 0x20 && (script.last().toInt() and 0xFF) == 0xAC

    /** Schnorr P2PK (`0x20 <32> 0xAC`) or ECDSA P2PK (`0x21 <33> 0xAB`). */
    private fun isPayToPubKey(script: ByteArray): Boolean =
        (script.size == 34 && (script[0].toInt() and 0xFF) == 0x20 && (script.last().toInt() and 0xFF) == 0xAC) ||
            (script.size == 35 && (script[0].toInt() and 0xFF) == 0x21 && (script.last().toInt() and 0xFF) == 0xAB)

    /** `OP_BLAKE2B OP_DATA_32 <hash> OP_EQUAL`. */
    fun isPayToScriptHash(script: ByteArray): Boolean =
        script.size == 35 && (script[0].toInt() and 0xFF) == 0xAA &&
            (script[1].toInt() and 0xFF) == 0x20 && (script.last().toInt() and 0xFF) == 0x87

    /** KasSigner's `is_hint_level`: chain 0 or 1, a non-hardened index. */
    private fun isHintLevel(chain: Long, index: Long): Boolean =
        chain in 0..1 && index in 0 until 0x8000_0000L

    /** A u64 read into a Long: anything above [MAX_SOMPI] (a negative Long is above it too) is
     *  refused, as is a running total past it. */
    private fun addValue(total: Long, value: Long): Long {
        if (value < 0 || value > MAX_SOMPI) throw KsptException("KSPT amounts exceed the total supply")
        val sum = total + value
        if (sum < 0 || sum > MAX_SOMPI) throw KsptException("KSPT amounts exceed the total supply")
        return sum
    }

    /** spkVersion(2), the length in its canonical form (one byte below 255, else 0xFF and a u16),
     *  then the script. */
    private fun appendScript(out: ByteArrayOutputStream, version: Int, script: ByteArray) {
        if (script.size > MAX_SCRIPT_V6) throw KsptException("KSPT script is too large (${script.size} bytes)")
        out.writeU16Le(version)
        if (script.size < LEN_ESCAPE) {
            out.write(script.size)
        } else {
            out.write(LEN_ESCAPE)
            out.writeU16Le(script.size)
        }
        out.write(script)
    }

    /** Minimal little-endian cursor for 0x06; running past the end is "truncated". */
    private class Reader(private val bytes: ByteArray) {
        var position = 0
            private set
        val remaining: Int get() = bytes.size - position

        fun skip(count: Int) { position += count }

        fun bytes(count: Int): ByteArray {
            if (count < 0 || position + count > bytes.size) throw KsptException("KSPT payload is truncated")
            return bytes.copyOfRange(position, position + count).also { position += count }
        }

        fun u8(): Int {
            if (position >= bytes.size) throw KsptException("KSPT payload is truncated")
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(): Int = u8() or (u8() shl 8)

        fun u32(): Long {
            var v = 0L
            for (i in 0 until 4) v = v or (u8().toLong() shl (8 * i))
            return v
        }

        fun u64(): Long {
            var v = 0L
            for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i))
            return v
        }

        /** 0x06's script length: one byte, or 0xFF then a u16 that must be at least 255. */
        fun scriptLen(): Int {
            val at = position
            val first = u8()
            if (first != 0xFF) return first
            val long = u16()
            if (long < 0xFF) throw KsptException("KSPT script length at byte $at isn't in its short form")
            return long
        }
    }

    private fun ByteBuffer.readU16(): Int = short.toInt() and 0xFFFF

    private fun ByteArrayOutputStream.writeU16Le(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32Le(value: Int) = writeU32Le(value.toLong())

    private fun ByteArrayOutputStream.writeU32Le(value: Long) {
        for (i in 0 until 4) write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeU64Le(value: Long) {
        for (i in 0 until 8) write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun String.hexToBytes(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        if (length % 2 != 0) throw KsptException("Odd-length hex")
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
