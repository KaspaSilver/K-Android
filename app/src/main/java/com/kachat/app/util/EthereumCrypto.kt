package com.kachat.app.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigDecimal

/*
 * The Ethereum pieces K needs for Buy Kaspa with Debit Card (iOS bc0e6ba9
 * `Utilities/EthereumCrypto.swift`, owner 2026-11-26): Keccak-256, EIP-55 addresses and EIP-712
 * typed-data hashing. Pure Kotlin with no key handling, so it's tested on its own
 * (`EthereumCryptoTest`, iOS `scripts/test_ethereum_crypto.swift`). The key and the signing are in
 * `EthereumAccount`.
 */

/** Keccak-256 as Ethereum uses it: the original Keccak padding (0x01), not NIST SHA3-256 (0x06). */
object Keccak256 {
    private val roundConstants: LongArray = listOf(
        0x0000000000000001uL, 0x0000000000008082uL, 0x800000000000808AuL, 0x8000000080008000uL,
        0x000000000000808BuL, 0x0000000080000001uL, 0x8000000080008081uL, 0x8000000000008009uL,
        0x000000000000008AuL, 0x0000000000000088uL, 0x0000000080008009uL, 0x000000008000000AuL,
        0x000000008000808BuL, 0x800000000000008BuL, 0x8000000000008089uL, 0x8000000000008003uL,
        0x8000000000008002uL, 0x8000000000000080uL, 0x000000000000800AuL, 0x800000008000000AuL,
        0x8000000080008081uL, 0x8000000000008080uL, 0x0000000080000001uL, 0x8000000080008008uL,
    ).map { it.toLong() }.toLongArray()
    private val rotations = intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)
    private val lanes = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)
    /** 1088 bits: what Keccak-256 absorbs per permutation. */
    private const val RATE = 136

    private fun rotl(x: Long, n: Int): Long = (x shl n) or (x ushr (64 - n))

    private fun permute(s: LongArray) {
        val c = LongArray(5)
        for (round in 0 until 24) {
            for (i in 0 until 5) c[i] = s[i] xor s[i + 5] xor s[i + 10] xor s[i + 15] xor s[i + 20]
            for (i in 0 until 5) {
                val t = c[(i + 4) % 5] xor rotl(c[(i + 1) % 5], 1)
                for (j in 0 until 25 step 5) s[j + i] = s[j + i] xor t
            }
            var t = s[1]
            for (i in 0 until 24) {
                val j = lanes[i]
                val next = s[j]
                s[j] = rotl(t, rotations[i])
                t = next
            }
            for (j in 0 until 25 step 5) {
                for (i in 0 until 5) c[i] = s[j + i]
                for (i in 0 until 5) s[j + i] = s[j + i] xor (c[(i + 1) % 5].inv() and c[(i + 2) % 5])
            }
            s[0] = s[0] xor roundConstants[round]
        }
    }

    fun hash(data: ByteArray): ByteArray {
        val padded = (data.size + 1).let { if (it % RATE == 0) it else it + (RATE - it % RATE) }
        val message = ByteArray(padded)
        System.arraycopy(data, 0, message, 0, data.size)
        message[data.size] = 0x01
        message[padded - 1] = (message[padded - 1].toInt() or 0x80).toByte()
        val state = LongArray(25)
        for (block in 0 until padded step RATE) {
            for (i in 0 until RATE / 8) {
                var lane = 0L
                for (b in 0 until 8) lane = lane or ((message[block + i * 8 + b].toLong() and 0xFF) shl (8 * b))
                state[i] = state[i] xor lane
            }
            permute(state)
        }
        val out = ByteArray(32)
        for (i in 0 until 4) {
            for (b in 0 until 8) out[i * 8 + b] = ((state[i] ushr (8 * b)) and 0xFF).toByte()
        }
        return out
    }
}

object EthereumHex {
    fun string(data: ByteArray): String {
        val out = StringBuilder(data.size * 2)
        for (byte in data) {
            val v = byte.toInt() and 0xFF
            out.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }

    /** "0x..." or plain hex to bytes; null if it isn't hex. */
    fun bytes(text: String): ByteArray? {
        var hex = text.lowercase()
        if (hex.startsWith("0x")) hex = hex.substring(2)
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** The EIP-55 mixed-case form of a 20-byte address. */
    fun checksumAddress(address: ByteArray): String {
        val lower = string(address)
        val hash = string(Keccak256.hash(lower.toByteArray(Charsets.UTF_8)))
        val out = StringBuilder("0x")
        for (i in lower.indices) {
            val char = lower[i]
            val nibble = Character.digit(hash[i], 16)
            out.append(if (char.isLetter() && nibble >= 8) char.uppercaseChar() else char)
        }
        return out.toString()
    }

    /** "0x" and 40 hex digits. */
    fun isAddress(text: String): Boolean =
        text.length == 42 && text.lowercase().startsWith("0x") && bytes(text)?.size == 20
}

/** Each struct type's fields, by type name. */
typealias EIP712Types = Map<String, List<EIP712.Field>>

/** EIP-712 typed-data hashing (`eth_signTypedData_v4`): structs, the atomic types and arrays. */
object EIP712 {
    class Failure(override val message: String) : Exception(message)

    data class Field(val name: String, val type: String)

    /** The 32 bytes a wallet signs: keccak256(0x19 0x01 ‖ domainSeparator ‖ hashStruct(message)). */
    fun digest(typedData: JsonObject): ByteArray {
        val types = parseTypes(typedData.get("types"))
        val primary = stringOf(typedData.get("primaryType"))
        if (primary == null || types[primary] == null) throw Failure("The typed data has no primary type.")
        val domain = typedData.get("domain")?.takeIf { it.isJsonObject }?.asJsonObject
        if (domain == null || types["EIP712Domain"] == null) throw Failure("The typed data has no domain.")
        val message = typedData.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw Failure("The typed data has no message.")
        val domainSeparator = hashStruct("EIP712Domain", domain, types)
        val messageHash = hashStruct(primary, message, types)
        return Keccak256.hash(byteArrayOf(0x19, 0x01) + domainSeparator + messageHash)
    }

    fun parseTypes(value: JsonElement?): EIP712Types {
        if (value == null || !value.isJsonObject) throw Failure("The typed data has no types.")
        val types = LinkedHashMap<String, List<Field>>()
        for ((name, fields) in value.asJsonObject.entrySet()) {
            if (!fields.isJsonArray || fields.asJsonArray.any { !it.isJsonObject }) throw Failure("Type $name is malformed.")
            types[name] = fields.asJsonArray.map { element ->
                val field = element.asJsonObject
                val fieldName = stringOf(field.get("name"))
                val fieldType = stringOf(field.get("type"))
                if (fieldName == null || fieldType == null) throw Failure("Type $name has a malformed field.")
                Field(fieldName, fieldType)
            }
        }
        return types
    }

    fun hashStruct(type: String, value: JsonObject, types: EIP712Types): ByteArray =
        Keccak256.hash(encodeData(type, value, types))

    fun typeHash(type: String, types: EIP712Types): ByteArray =
        Keccak256.hash(encodeType(type, types).toByteArray(Charsets.UTF_8))

    /** "Mail(Person from,Person to,string contents)Person(string name,address wallet)": the type,
     *  then the struct types it uses, sorted by name. */
    fun encodeType(type: String, types: EIP712Types): String {
        val found = HashSet<String>()
        collect(type, types, found)
        found.remove(type)
        return (listOf(type) + found.sorted()).joinToString("") { name ->
            val fields = types[name] ?: throw Failure("Unknown type $name.")
            "$name(" + fields.joinToString(",") { "${it.type} ${it.name}" } + ")"
        }
    }

    private fun collect(type: String, types: EIP712Types, found: MutableSet<String>) {
        val base = baseType(type)
        val fields = types[base] ?: return
        if (base in found) return
        found.add(base)
        for (field in fields) collect(field.type, types, found)
    }

    /** "Person[]" or "uint256[3]" → "Person" / "uint256". */
    private fun baseType(type: String): String {
        val bracket = type.indexOf('[')
        return if (bracket < 0) type else type.substring(0, bracket)
    }

    fun encodeData(type: String, value: JsonObject, types: EIP712Types): ByteArray {
        val fields = types[type] ?: throw Failure("Unknown type $type.")
        var out = typeHash(type, types)
        for (field in fields) {
            val fieldValue = value.get(field.name) ?: throw Failure("$type has no ${field.name}.")
            out += encodeValue(field.type, fieldValue, types)
        }
        return out
    }

    fun encodeValue(type: String, value: JsonElement, types: EIP712Types): ByteArray {
        val bracket = type.lastIndexOf('[')
        if (type.endsWith("]") && bracket >= 0) {
            val itemType = type.substring(0, bracket)
            if (!value.isJsonArray) throw Failure("$type needs a list.")
            val items: JsonArray = value.asJsonArray
            val size = type.substring(bracket + 1, type.length - 1)
            if (size.isNotEmpty() && size.toIntOrNull() != items.size()) throw Failure("$type has the wrong length.")
            var joined = ByteArray(0)
            for (item in items) joined += encodeValue(itemType, item, types)
            return Keccak256.hash(joined)
        }
        if (types[type] != null) {
            if (!value.isJsonObject) throw Failure("$type needs an object.")
            return hashStruct(type, value.asJsonObject, types)
        }
        when (type) {
            "string" -> {
                val text = stringOf(value) ?: throw Failure("A string field isn't text.")
                return Keccak256.hash(text.toByteArray(Charsets.UTF_8))
            }
            "bytes" -> {
                val bytes = stringOf(value)?.let { EthereumHex.bytes(it) } ?: throw Failure("A bytes field isn't hex.")
                return Keccak256.hash(bytes)
            }
            "bool" -> {
                val flag = when {
                    isBoolean(value) -> value.asBoolean
                    stringOf(value) == "true" -> true
                    stringOf(value) == "false" -> false
                    else -> throw Failure("A bool field isn't true or false.")
                }
                return word(if (flag) 1 else 0)
            }
            "address" -> {
                val text = stringOf(value)
                val bytes = text?.takeIf { EthereumHex.isAddress(it) }?.let { EthereumHex.bytes(it) }
                    ?: throw Failure("An address field isn't an address.")
                return ByteArray(12) + bytes
            }
        }
        if (type.startsWith("uint") || type.startsWith("int")) {
            // negative ints aren't needed by anything K signs, and are refused
            return uint256(value)
        }
        if (type.startsWith("bytes")) {
            val size = type.substring(5).toIntOrNull()
            if (size != null && size in 1..32) {
                val bytes = stringOf(value)?.let { EthereumHex.bytes(it) }
                if (bytes == null || bytes.size != size) throw Failure("A $type field has the wrong length.")
                return bytes + ByteArray(32 - size)
            }
        }
        throw Failure("K can't sign the type $type.")
    }

    /** A JSON true / false. */
    private fun isBoolean(value: JsonElement?): Boolean =
        value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean

    private fun isNumber(value: JsonElement?): Boolean =
        value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber

    /** A JSON string's text; null for anything else. */
    private fun stringOf(value: JsonElement?): String? =
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null

    private fun word(value: Int): ByteArray = ByteArray(32).also { it[31] = value.toByte() }

    private val MAX_SAFE_INTEGER = BigDecimal("9007199254740992")
    private val SMALL_LIMIT = BigDecimal("1e18")

    /** A JSON number that is whole and not negative, below [limit]; null otherwise. */
    private fun wholeNumber(value: JsonElement, limit: BigDecimal): Long? {
        val number = try { BigDecimal(value.asJsonPrimitive.asString) } catch (e: Exception) { return null }
        if (number.signum() < 0 || number >= limit) return null
        return try { number.longValueExact() } catch (e: ArithmeticException) { null }
    }

    /** A non-negative integer (a JSON number, a decimal string or a 0x hex string) as 32 bytes. */
    fun uint256(value: JsonElement): ByteArray {
        if (isNumber(value)) {
            val whole = wholeNumber(value, MAX_SAFE_INTEGER)
                ?: throw Failure("A number field isn't a whole number K can sign.")
            return uint256Text(whole.toString())
        }
        val text = stringOf(value)?.trim { it == ' ' || it == '\t' }
        if (text.isNullOrEmpty()) throw Failure("A number field is empty.")
        return uint256Text(text)
    }

    private fun uint256Text(text: String): ByteArray {
        if (text.lowercase().startsWith("0x")) {
            var hex = text.substring(2)
            if (hex.length % 2 == 1) hex = "0$hex"
            val bytes = EthereumHex.bytes(hex) ?: throw Failure("A number field isn't hex.")
            val trimmed = bytes.dropWhile { it == 0.toByte() }.toByteArray()
            if (trimmed.size > 32) throw Failure("A number field is too large.")
            return ByteArray(32 - trimmed.size) + trimmed
        }
        if (!text.all { it in '0'..'9' }) throw Failure("A number field isn't a number.")
        val out = IntArray(32)
        for (char in text) {
            var carry = char - '0'
            for (i in 31 downTo 0) {
                val v = out[i] * 10 + carry
                out[i] = v and 0xFF
                carry = v shr 8
            }
            if (carry != 0) throw Failure("A number field is too large.")
        }
        return ByteArray(32) { out[it].toByte() }
    }

    /** A decimal string, a 0x hex string or a JSON number as a whole non-negative number (USDC
     *  amounts, deadlines, nonces); null otherwise. Up to 2^63 - 1 here (iOS: a UInt64), which
     *  nothing K reads comes near. */
    fun smallUInt(value: JsonElement?): Long? {
        if (value == null || !value.isJsonPrimitive) return null
        if (isNumber(value)) return wholeNumber(value, SMALL_LIMIT)
        val text = stringOf(value) ?: return null
        if (text.lowercase().startsWith("0x")) {
            val hex = text.substring(2)
            if (hex.isEmpty() || !hex.all { Character.digit(it, 16) >= 0 }) return null
            return hex.toLongOrNull(16)
        }
        val digits = text.removePrefix("+")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        return digits.toLongOrNull()
    }
}
