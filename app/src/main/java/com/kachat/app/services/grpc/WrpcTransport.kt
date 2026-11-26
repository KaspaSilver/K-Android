package com.kachat.app.services.grpc

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import protowire.Messages
import protowire.Rpc
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit

// Kaspa nodes serve the same RPC over WebSocket ("wRPC", rusty-kaspa) as over gRPC. K speaks its
// JSON encoding as a second transport (iOS 961fc05a `WRPCTransport.swift`): a `wss://` node typed
// into Connection Settings > Node, the public nodes [PublicNodeResolver] finds when no gRPC node
// can be reached (DPI, firewalls that block gRPC's HTTP/2), and Automatic Scan's wss seeds.
// Everything above [KaspadConnection] - the pool, the block scanners, sends - keeps working in
// protobuf: this file turns each request into its wRPC JSON message and each JSON answer back into
// the `KaspadResponse` gRPC would have sent, so both transports share request matching, timeouts,
// health tracking and reconnects.
//
// The wire format (checked against kaspad 2.1.0's public nodes):
// - request:      {"id":7,"method":"getUtxosByAddresses","params":{"addresses":[...]}}
// - answer:       {"id":7,"method":"getUtxosByAddresses","params":{...}}
// - error:        {"id":7,"method":"...","error":{"code":0,"message":"...","data":null}}
// - subscribe:    method "subscribe" / "unsubscribe" with {"UtxosChanged":{"addresses":[...]}} or
//                 {"BlockAdded":{}}; answered {"id":N} / {}
// - notification: {"method":"utxosChangedNotification","params":{"UtxosChanged":{"added":[...],"removed":[...]}}}
//                 {"method":"blockAddedNotification","params":{"BlockAdded":{"block":{...}}}}
// - a script public key is one hex string: the 2-byte version, then the script.
// - an unknown method name closes the socket, so only the methods below are ever sent.

/** The JSON encoding of wRPC, both ways. Pure: unit-tested in WrpcJsonTest. */
object WrpcJson {
    class UnsupportedRequestException : IllegalArgumentException("This request can't be sent over wRPC")

    // MARK: Requests

    /** The method and params for [message]. Throws for a request K never sends. */
    fun request(message: Messages.KaspadRequest): Pair<String, JsonObject> = when (message.payloadCase) {
        Messages.KaspadRequest.PayloadCase.GETINFOREQUEST -> "getInfo" to JsonObject()
        Messages.KaspadRequest.PayloadCase.GETUTXOSBYADDRESSESREQUEST ->
            "getUtxosByAddresses" to JsonObject().apply { add("addresses", strings(message.getUtxosByAddressesRequest.addressesList)) }
        Messages.KaspadRequest.PayloadCase.SUBMITTRANSACTIONREQUEST -> "submitTransaction" to JsonObject().apply {
            add("transaction", transactionJson(message.submitTransactionRequest.transaction))
            addProperty("allowOrphan", message.submitTransactionRequest.allowOrphan)
        }
        Messages.KaspadRequest.PayloadCase.NOTIFYUTXOSCHANGEDREQUEST -> {
            val r = message.notifyUtxosChangedRequest
            val method = if (r.command == Rpc.RpcNotifyCommand.NOTIFY_STOP) "unsubscribe" else "subscribe"
            method to JsonObject().apply { add("UtxosChanged", JsonObject().apply { add("addresses", strings(r.addressesList)) }) }
        }
        Messages.KaspadRequest.PayloadCase.NOTIFYBLOCKADDEDREQUEST -> {
            val method = if (message.notifyBlockAddedRequest.command == Rpc.RpcNotifyCommand.NOTIFY_STOP) "unsubscribe" else "subscribe"
            method to JsonObject().apply { add("BlockAdded", JsonObject()) }
        }
        Messages.KaspadRequest.PayloadCase.GETPEERADDRESSESREQUEST -> "getPeerAddresses" to JsonObject()
        Messages.KaspadRequest.PayloadCase.GETCONNECTEDPEERINFOREQUEST -> "getConnectedPeerInfo" to JsonObject()
        Messages.KaspadRequest.PayloadCase.GETBLOCKDAGINFOREQUEST -> "getBlockDagInfo" to JsonObject()
        Messages.KaspadRequest.PayloadCase.GETCURRENTNETWORKREQUEST -> "getCurrentNetwork" to JsonObject()
        Messages.KaspadRequest.PayloadCase.GETMEMPOOLENTRYREQUEST -> {
            val r = message.getMempoolEntryRequest
            "getMempoolEntry" to JsonObject().apply {
                addProperty("transactionId", r.txId)
                addProperty("includeOrphanPool", r.includeOrphanPool)
                addProperty("filterTransactionPool", r.filterTransactionPool)
            }
        }
        Messages.KaspadRequest.PayloadCase.GETFEEESTIMATEREQUEST -> "getFeeEstimate" to JsonObject()
        else -> throw UnsupportedRequestException()
    }

    /** A transaction as wRPC takes it. `mass` and `storageMass` must match when both are given
     *  (the node refuses the request otherwise), so both carry the protobuf's `storage_mass`. */
    fun transactionJson(tx: Rpc.RpcTransaction): JsonObject = JsonObject().apply {
        addProperty("version", tx.version.toLong() and 0xFFFFFFFFL)
        add("inputs", JsonArray().apply {
            for (input in tx.inputsList) add(JsonObject().apply {
                add("previousOutpoint", JsonObject().apply {
                    addProperty("transactionId", input.previousOutpoint.transactionId)
                    addProperty("index", input.previousOutpoint.index.toLong() and 0xFFFFFFFFL)
                })
                addProperty("signatureScript", input.signatureScript)
                add("sequence", unsigned(input.sequence))
                addProperty("sigOpCount", input.sigOpCount.toLong() and 0xFFFFFFFFL)
                addProperty("computeBudget", input.computeBudget.toLong() and 0xFFFFFFFFL)
            })
        })
        add("outputs", JsonArray().apply {
            for (output in tx.outputsList) add(JsonObject().apply {
                add("value", unsigned(output.amount))
                addProperty("scriptPublicKey", scriptHex(output.scriptPublicKey.version, output.scriptPublicKey.scriptPublicKey))
                if (output.hasCovenant()) {
                    add("covenant", JsonObject().apply {
                        addProperty("authorizingInput", output.covenant.authorizingInput.toLong() and 0xFFFFFFFFL)
                        addProperty("covenantId", output.covenant.covenantId)
                    })
                } else {
                    add("covenant", JsonNull.INSTANCE)
                }
            })
        })
        add("lockTime", unsigned(tx.lockTime))
        addProperty("subnetworkId", tx.subnetworkId)
        add("gas", unsigned(tx.gas))
        addProperty("payload", tx.payload)
        add("mass", unsigned(tx.storageMass))
        add("storageMass", unsigned(tx.storageMass))
    }

    /** The whole request frame, as text. */
    fun requestFrame(message: Messages.KaspadRequest): String {
        val (method, params) = request(message)
        return JsonObject().apply {
            add("id", unsigned(message.id))
            addProperty("method", method)
            add("params", params)
        }.toString()
    }

    // MARK: Answers and notifications

    /** How much of a block-added notification to read: nothing while no scanner listens (a block
     *  left arriving before its stop is answered costs only the parse), the payload transactions a
     *  scan reads, or the whole block while something shows it (the Explorer). */
    enum class BlockDetail { NONE, PAYLOADS, FULL }

    /**
     * Decodes one text frame into the `KaspadResponse` gRPC would have sent, or null for one that
     * means nothing here. [typeOf] takes the request type sent under an id (wRPC answers a subscribe
     * and an unsubscribe of either kind with the same method name, and an error answer with no
     * payload to tell its type by).
     */
    fun decode(
        text: String,
        typeOf: (Long) -> Messages.KaspadRequest.PayloadCase?,
        blockDetail: () -> BlockDetail = { BlockDetail.PAYLOADS },
    ): Messages.KaspadResponse? {
        val root = try { JsonParser.parseString(text) } catch (e: Exception) { return null }
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val method = str(obj["method"])
        val rawId = obj["id"]
        if (rawId != null && !rawId.isJsonNull) {
            val id = u64(rawId)
            // An answer to nothing sent on this socket (or sent and since forgotten) is dropped.
            val type = typeOf(id) ?: return null
            val error = obj["error"]
            if (error != null && !error.isJsonNull) {
                val message = if (error.isJsonObject) str(error.asJsonObject["message"]) else error.toString()
                return errorAnswer(message.ifEmpty { "Node error" }, type)?.setId(id)?.build()
            }
            val params = obj["params"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            return answer(params, type)?.setId(id)?.build()
        }

        val params = obj["params"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return when (method) {
            "utxosChangedNotification" -> {
                val body = params["UtxosChanged"]?.takeIf { it.isJsonObject }?.asJsonObject ?: params
                val n = Rpc.UtxosChangedNotificationMessage.newBuilder()
                    .addAllAdded(objects(body["added"]).map(::utxoEntry))
                    .addAllRemoved(objects(body["removed"]).map(::utxoEntry))
                Messages.KaspadResponse.newBuilder().setUtxosChangedNotification(n).build()
            }
            "blockAddedNotification" -> {
                val detail = blockDetail()
                val body = params["BlockAdded"]?.takeIf { it.isJsonObject }?.asJsonObject ?: params
                val block = body["block"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                val rpcBlock = Rpc.RpcBlock.newBuilder()
                if (detail != BlockDetail.NONE) {
                    // The header always (a scan dates its hits by the block's time), the verbose
                    // data and every transaction for the Explorer, else only the transactions that
                    // carry a payload: ten blocks a second arrive (audit 2026-11-25 C3).
                    block["header"]?.takeIf { it.isJsonObject }?.let { rpcBlock.setHeader(blockHeader(it.asJsonObject)) }
                    val txs = objects(block["transactions"])
                    if (detail == BlockDetail.FULL) {
                        block["verboseData"]?.takeIf { it.isJsonObject }?.let { rpcBlock.setVerboseData(blockVerboseData(it.asJsonObject)) }
                        rpcBlock.addAllTransactions(txs.map(::transaction))
                    } else {
                        rpcBlock.addAllTransactions(txs.filter { str(it["payload"]).isNotEmpty() }.map(::transaction))
                    }
                }
                Messages.KaspadResponse.newBuilder()
                    .setBlockAddedNotification(Rpc.BlockAddedNotificationMessage.newBuilder().setBlock(rpcBlock))
                    .build()
            }
            else -> null
        }
    }

    /** An error answer in the shape of the request it answers, as gRPC sends one. */
    fun errorAnswer(text: String, type: Messages.KaspadRequest.PayloadCase): Messages.KaspadResponse.Builder? {
        val error = Rpc.RPCError.newBuilder().setMessage(text).build()
        val b = Messages.KaspadResponse.newBuilder()
        return when (type) {
            Messages.KaspadRequest.PayloadCase.GETINFOREQUEST -> b.setGetInfoResponse(Rpc.GetInfoResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.GETUTXOSBYADDRESSESREQUEST -> b.setGetUtxosByAddressesResponse(Rpc.GetUtxosByAddressesResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.SUBMITTRANSACTIONREQUEST -> b.setSubmitTransactionResponse(Rpc.SubmitTransactionResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.NOTIFYUTXOSCHANGEDREQUEST -> b.setNotifyUtxosChangedResponse(Rpc.NotifyUtxosChangedResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.NOTIFYBLOCKADDEDREQUEST -> b.setNotifyBlockAddedResponse(Rpc.NotifyBlockAddedResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.GETPEERADDRESSESREQUEST -> b.setGetPeerAddressesResponse(Rpc.GetPeerAddressesResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.GETCONNECTEDPEERINFOREQUEST -> b.setGetConnectedPeerInfoResponse(Rpc.GetConnectedPeerInfoResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.GETBLOCKDAGINFOREQUEST -> b.setGetBlockDagInfoResponse(Rpc.GetBlockDagInfoResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.GETCURRENTNETWORKREQUEST -> b.setGetCurrentNetworkResponse(Rpc.GetCurrentNetworkResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.GETMEMPOOLENTRYREQUEST -> b.setGetMempoolEntryResponse(Rpc.GetMempoolEntryResponseMessage.newBuilder().setError(error))
            Messages.KaspadRequest.PayloadCase.GETFEEESTIMATEREQUEST -> b.setGetFeeEstimateResponse(Rpc.GetFeeEstimateResponseMessage.newBuilder().setError(error))
            else -> null
        }
    }

    private fun answer(p: JsonObject, type: Messages.KaspadRequest.PayloadCase): Messages.KaspadResponse.Builder? {
        val b = Messages.KaspadResponse.newBuilder()
        return when (type) {
            Messages.KaspadRequest.PayloadCase.GETINFOREQUEST -> b.setGetInfoResponse(
                Rpc.GetInfoResponseMessage.newBuilder()
                    .setMempoolSize(u64(p["mempoolSize"]))
                    .setServerVersion(str(p["serverVersion"]))
                    .setIsUtxoIndexed(bool(p["isUtxoIndexed"]))
                    .setIsSynced(bool(p["isSynced"]))
                    .setHasNotifyCommand(bool(p["hasNotifyCommand"]))
                    .setHasMessageId(bool(p["hasMessageId"]))
            )
            Messages.KaspadRequest.PayloadCase.GETUTXOSBYADDRESSESREQUEST -> b.setGetUtxosByAddressesResponse(
                Rpc.GetUtxosByAddressesResponseMessage.newBuilder().addAllEntries(objects(p["entries"]).map(::utxoEntry))
            )
            Messages.KaspadRequest.PayloadCase.SUBMITTRANSACTIONREQUEST -> b.setSubmitTransactionResponse(
                Rpc.SubmitTransactionResponseMessage.newBuilder().setTransactionId(str(p["transactionId"]))
            )
            Messages.KaspadRequest.PayloadCase.NOTIFYUTXOSCHANGEDREQUEST ->
                b.setNotifyUtxosChangedResponse(Rpc.NotifyUtxosChangedResponseMessage.getDefaultInstance())
            Messages.KaspadRequest.PayloadCase.NOTIFYBLOCKADDEDREQUEST ->
                b.setNotifyBlockAddedResponse(Rpc.NotifyBlockAddedResponseMessage.getDefaultInstance())
            Messages.KaspadRequest.PayloadCase.GETPEERADDRESSESREQUEST -> b.setGetPeerAddressesResponse(
                Rpc.GetPeerAddressesResponseMessage.newBuilder()
                    .addAllAddresses(objects(p["knownAddresses"]).mapNotNull(::knownAddress))
                    .addAllBannedAddresses(objects(p["bannedAddresses"]).mapNotNull(::knownAddress))
            )
            Messages.KaspadRequest.PayloadCase.GETCONNECTEDPEERINFOREQUEST -> b.setGetConnectedPeerInfoResponse(
                Rpc.GetConnectedPeerInfoResponseMessage.newBuilder().addAllInfos(objects(p["peerInfo"]).map { j ->
                    Rpc.GetConnectedPeerInfoMessage.newBuilder()
                        .setId(str(j["id"]))
                        .setAddress(j["address"]?.takeIf { it.isJsonObject }?.let { hostPort(it.asJsonObject) } ?: "")
                        .setLastPingDuration(i64(j["last_ping_duration"] ?: j["lastPingDuration"]))
                        .setIsOutbound(bool(j["is_outbound"] ?: j["isOutbound"]))
                        .setTimeOffset(i64(j["time_offset"] ?: j["timeOffset"]))
                        .setUserAgent(str(j["user_agent"] ?: j["userAgent"]))
                        .setAdvertisedProtocolVersion(u32(j["advertised_protocol_version"] ?: j["advertisedProtocolVersion"]))
                        .setTimeConnected(i64(j["time_connected"] ?: j["timeConnected"]))
                        .setIsIbdPeer(bool(j["is_ibd_peer"] ?: j["isIbdPeer"]))
                        .build()
                })
            )
            Messages.KaspadRequest.PayloadCase.GETBLOCKDAGINFOREQUEST -> b.setGetBlockDagInfoResponse(
                Rpc.GetBlockDagInfoResponseMessage.newBuilder()
                    .setNetworkName(networkName(str(p["network"])))
                    .setBlockCount(u64(p["blockCount"]))
                    .setHeaderCount(u64(p["headerCount"]))
                    .addAllTipHashes(stringList(p["tipHashes"]))
                    .setDifficulty(double(p["difficulty"]))
                    .setPastMedianTime(i64(p["pastMedianTime"]))
                    .addAllVirtualParentHashes(stringList(p["virtualParentHashes"]))
                    .setPruningPointHash(str(p["pruningPointHash"]))
                    .setVirtualDaaScore(u64(p["virtualDaaScore"]))
                    .setSink(str(p["sink"]))
            )
            Messages.KaspadRequest.PayloadCase.GETCURRENTNETWORKREQUEST -> b.setGetCurrentNetworkResponse(
                Rpc.GetCurrentNetworkResponseMessage.newBuilder().setCurrentNetwork(str(p["network"]))
            )
            Messages.KaspadRequest.PayloadCase.GETMEMPOOLENTRYREQUEST -> {
                val r = Rpc.GetMempoolEntryResponseMessage.newBuilder()
                p["mempoolEntry"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { e ->
                    val entry = Rpc.RpcMempoolEntry.newBuilder()
                        .setFee(u64(e["fee"]))
                        .setIsOrphan(bool(e["is_orphan"] ?: e["isOrphan"]))
                    e["transaction"]?.takeIf { it.isJsonObject }?.let { entry.setTransaction(transaction(it.asJsonObject)) }
                    r.setEntry(entry)
                }
                b.setGetMempoolEntryResponse(r)
            }
            Messages.KaspadRequest.PayloadCase.GETFEEESTIMATEREQUEST -> {
                val r = Rpc.GetFeeEstimateResponseMessage.newBuilder()
                p["estimate"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { e ->
                    val estimate = Rpc.RpcFeeEstimate.newBuilder()
                    e["priorityBucket"]?.takeIf { it.isJsonObject }?.let { estimate.setPriorityBucket(bucket(it.asJsonObject)) }
                    estimate.addAllNormalBuckets(objects(e["normalBuckets"]).map(::bucket))
                    estimate.addAllLowBuckets(objects(e["lowBuckets"]).map(::bucket))
                    r.setEstimate(estimate)
                }
                b.setGetFeeEstimateResponse(r)
            }
            else -> null
        }
    }

    /** wRPC names networks "mainnet" / "testnet-10"; gRPC's getBlockDagInfo says "kaspa-mainnet",
     *  which is what the registry's network checks compare against ([belongsToRunningNetwork]). */
    fun networkName(name: String): String = if (name.isEmpty() || name.startsWith("kaspa-")) name else "kaspa-$name"

    // MARK: Pieces

    /** rusty-kaspa's `RpcHeader` in JSON: hashes as hex strings, `parentsByLevel` as arrays of them,
     *  `timestamp` in milliseconds. */
    private fun blockHeader(j: JsonObject): Rpc.RpcBlockHeader {
        val h = Rpc.RpcBlockHeader.newBuilder()
            .setHash(str(j["hash"]))
            .setVersion(u32(j["version"]))
        val byLevel = j["parentsByLevel"]
        if (byLevel != null && byLevel.isJsonArray) {
            for (level in byLevel.asJsonArray) {
                h.addParents(Rpc.RpcBlockLevelParents.newBuilder().addAllParentHashes(stringList(level)))
            }
        } else {
            for (level in objects(j["parents"])) {
                h.addParents(Rpc.RpcBlockLevelParents.newBuilder().addAllParentHashes(stringList(level["parentHashes"])))
            }
        }
        return h.setHashMerkleRoot(str(j["hashMerkleRoot"]))
            .setAcceptedIdMerkleRoot(str(j["acceptedIdMerkleRoot"]))
            .setUtxoCommitment(str(j["utxoCommitment"]))
            .setTimestamp(i64(j["timestamp"]))
            .setBits(u32(j["bits"]))
            .setNonce(u64(j["nonce"]))
            .setDaaScore(u64(j["daaScore"]))
            .setBlueWork(str(j["blueWork"]))
            .setBlueScore(u64(j["blueScore"]))
            .setPruningPoint(str(j["pruningPoint"]))
            .build()
    }

    /** rusty-kaspa's `RpcBlockVerboseData` in JSON. */
    private fun blockVerboseData(j: JsonObject): Rpc.RpcBlockVerboseData =
        Rpc.RpcBlockVerboseData.newBuilder()
            .setHash(str(j["hash"]))
            .setDifficulty(double(j["difficulty"]))
            .setSelectedParentHash(str(j["selectedParentHash"]))
            .addAllTransactionIds(stringList(j["transactionIds"]))
            .setIsHeaderOnly(bool(j["isHeaderOnly"]))
            .setBlueScore(u64(j["blueScore"]))
            .addAllChildrenHashes(stringList(j["childrenHashes"]))
            .addAllMergeSetBluesHashes(stringList(j["mergeSetBluesHashes"]))
            .addAllMergeSetRedsHashes(stringList(j["mergeSetRedsHashes"]))
            .setIsChainBlock(bool(j["isChainBlock"]))
            .build()

    private fun bucket(j: JsonObject): Rpc.RpcFeerateBucket =
        Rpc.RpcFeerateBucket.newBuilder().setFeerate(double(j["feerate"])).setEstimatedSeconds(double(j["estimatedSeconds"])).build()

    private fun utxoEntry(j: JsonObject): Rpc.RpcUtxosByAddressesEntry {
        val entry = Rpc.RpcUtxosByAddressesEntry.newBuilder().setAddress(str(j["address"]))
        j["outpoint"]?.takeIf { it.isJsonObject }?.let { entry.setOutpoint(outpoint(it.asJsonObject)) }
        j["utxoEntry"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
            entry.setUtxoEntry(
                Rpc.RpcUtxoEntry.newBuilder()
                    .setAmount(u64(u["amount"]))
                    .setScriptPublicKey(scriptPublicKey(u["scriptPublicKey"]))
                    .setBlockDaaScore(u64(u["blockDaaScore"]))
                    .setIsCoinbase(bool(u["isCoinbase"]))
                    .setCovenantId(str(u["covenantId"]))
            )
        }
        return entry.build()
    }

    private fun outpoint(j: JsonObject): Rpc.RpcOutpoint =
        Rpc.RpcOutpoint.newBuilder().setTransactionId(str(j["transactionId"])).setIndex(u32(j["index"])).build()

    fun transaction(j: JsonObject): Rpc.RpcTransaction {
        val tx = Rpc.RpcTransaction.newBuilder().setVersion(u32(j["version"]))
        for (i in objects(j["inputs"])) {
            val input = Rpc.RpcTransactionInput.newBuilder()
            i["previousOutpoint"]?.takeIf { it.isJsonObject }?.let { input.setPreviousOutpoint(outpoint(it.asJsonObject)) }
            input.setSignatureScript(str(i["signatureScript"]))
                .setSequence(u64(i["sequence"]))
                .setSigOpCount(u32(i["sigOpCount"]))
            val budget = i["computeBudget"]
            if (budget != null && !budget.isJsonNull) input.setComputeBudget(u32(budget))
            tx.addInputs(input)
        }
        for (o in objects(j["outputs"])) {
            val output = Rpc.RpcTransactionOutput.newBuilder()
                .setAmount(u64(o["value"] ?: o["amount"]))
                .setScriptPublicKey(scriptPublicKey(o["scriptPublicKey"]))
            o["covenant"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { c ->
                output.setCovenant(
                    Rpc.RpcCovenantBinding.newBuilder()
                        .setAuthorizingInput(u32(c["authorizingInput"]))
                        .setCovenantId(str(c["covenantId"]))
                )
            }
            o["verboseData"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { v ->
                output.setVerboseData(
                    Rpc.RpcTransactionOutputVerboseData.newBuilder()
                        .setScriptPublicKeyType(str(v["scriptPublicKeyType"]))
                        .setScriptPublicKeyAddress(str(v["scriptPublicKeyAddress"]))
                )
            }
            tx.addOutputs(output)
        }
        tx.setLockTime(u64(j["lockTime"]))
            .setSubnetworkId(str(j["subnetworkId"]))
            .setGas(u64(j["gas"]))
            .setPayload(str(j["payload"]))
            .setStorageMass(u64(j["storageMass"] ?: j["mass"]))
        j["verboseData"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { v ->
            tx.setVerboseData(
                Rpc.RpcTransactionVerboseData.newBuilder()
                    .setTransactionId(str(v["transactionId"]))
                    .setHash(str(v["hash"]))
                    .setComputeMass(u64(v["computeMass"]))
                    .setBlockHash(str(v["blockHash"]))
                    .setBlockTime(u64(v["blockTime"]))
            )
        }
        return tx.build()
    }

    /** "0000" + script hex (the version as two big-endian bytes, then the script), or an object. */
    private fun scriptPublicKey(value: JsonElement?): Rpc.RpcScriptPublicKey {
        val spk = Rpc.RpcScriptPublicKey.newBuilder()
        if (value == null || value.isJsonNull) return spk.build()
        if (value.isJsonPrimitive) {
            val hex = value.asString
            if (hex.length < 4) return spk.build()
            spk.setVersion(hex.substring(0, 4).toIntOrNull(16) ?: 0)
            spk.setScriptPublicKey(hex.substring(4))
        } else if (value.isJsonObject) {
            val j = value.asJsonObject
            spk.setVersion(u32(j["version"]))
            spk.setScriptPublicKey(str(j["script"] ?: j["scriptPublicKey"]))
        }
        return spk.build()
    }

    fun scriptHex(version: Int, script: String): String =
        String.format("%04x", minOf(version.toLong() and 0xFFFFFFFFL, 0xFFFFL)) + script

    private fun knownAddress(j: JsonObject): Rpc.GetPeerAddressesKnownAddressMessage? {
        val addr = hostPort(j) ?: return null
        return Rpc.GetPeerAddressesKnownAddressMessage.newBuilder().setAddr(addr).build()
    }

    /** {"ip": "...", "port": 16111} as gRPC writes it: "host:port", "[ipv6]:port". An IPv4 address
     *  mapped into IPv6 ("::ffff:1.2.3.4") is written as plain IPv4. */
    fun hostPort(j: JsonObject): String? {
        var ip = str(j["ip"])
        val port = u64(j["port"])
        if (ip.isEmpty() || port <= 0) return null
        if (ip.lowercase().startsWith("::ffff:") && ip.substring(7).contains('.')) ip = ip.substring(7)
        return if (ip.contains(':')) "[$ip]:$port" else "$ip:$port"
    }

    // MARK: Values: a number or a string holding one (proto3 JSON writes u64 as strings), never a crash

    private fun unsigned(value: Long): JsonPrimitive =
        if (value >= 0) JsonPrimitive(value) else JsonPrimitive(BigInteger(java.lang.Long.toUnsignedString(value)))

    /** An unsigned 64-bit value in a Long (two's complement past Long.MAX_VALUE, as protobuf keeps
     *  uint64); 0 for anything that isn't a whole number. */
    internal fun u64(value: JsonElement?): Long {
        val text = primitiveText(value) ?: return 0
        return try {
            val n = BigDecimal(text)
            if (n.signum() < 0) 0 else n.toBigInteger().toLong()
        } catch (e: Exception) {
            0
        }
    }

    private fun u32(value: JsonElement?): Int = (u64(value).coerceIn(0L, 0xFFFFFFFFL)).toInt()

    private fun i64(value: JsonElement?): Long {
        val text = primitiveText(value) ?: return 0
        return try {
            BigDecimal(text).toBigInteger().toLong()
        } catch (e: Exception) {
            0
        }
    }

    private fun double(value: JsonElement?): Double = primitiveText(value)?.toDoubleOrNull() ?: 0.0

    private fun bool(value: JsonElement?): Boolean =
        value != null && value.isJsonPrimitive && value.asJsonPrimitive.let { if (it.isBoolean) it.asBoolean else it.asString == "true" }

    private fun str(value: JsonElement?): String =
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else ""

    private fun primitiveText(value: JsonElement?): String? =
        if (value != null && value.isJsonPrimitive) value.asString.trim().ifEmpty { null } else null

    private fun strings(values: List<String>): JsonArray = JsonArray().apply { values.forEach { add(it) } }

    private fun stringList(value: JsonElement?): List<String> =
        if (value != null && value.isJsonArray) value.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null } else emptyList()

    private fun objects(value: JsonElement?): List<JsonObject> =
        if (value != null && value.isJsonArray) value.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null } else emptyList()
}

/**
 * One WebSocket to a node's wRPC JSON listener (iOS `WRPCSocket`), over OkHttp. Frames are decoded
 * on OkHttp's reader thread, in order, and handed to [onMessage] as gRPC responses.
 */
internal class WrpcSocket(
    private val url: String,
    private val scope: CoroutineScope,
    private val onMessage: (Messages.KaspadResponse) -> Unit,
    /** Called once, when the socket closes or fails (not after [close]). */
    private val onClosed: (Throwable?) -> Unit,
    private val blockDetail: () -> WrpcJson.BlockDetail,
) {
    private val lock = Any()
    private var webSocket: WebSocket? = null
    @Volatile private var opened = false
    @Volatile private var finished = false

    /** Requests sent and not yet answered, by id, with when they went. An id the node never
     *  answers would stay for the socket's life, so old ones are dropped ([trimSentTypes]): no
     *  request waits that long (audit 2026-11-25 C10). */
    private val sentTypes = LinkedHashMap<Long, Pair<Messages.KaspadRequest.PayloadCase, Long>>()

    fun open() {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            finish(e)
            return
        }
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handle(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(java.io.IOException("Socket closed ($code)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(java.io.IOException("Socket closed ($code)"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(t)
            }
        })
        synchronized(lock) { webSocket = socket }
        // The handshake is bounded here, not by a read timeout (there is none: a quiet socket is
        // a healthy one, audit 2026-11-25 B11).
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!opened && !finished) {
                socket.cancel()
                finish(java.io.IOException("WebSocket handshake timed out"))
            }
        }
    }

    /** Sends [request]; false when it can't be (closed, or a request wRPC has no method for). */
    fun send(request: Messages.KaspadRequest): Boolean {
        val text = try {
            WrpcJson.requestFrame(request)
        } catch (e: WrpcJson.UnsupportedRequestException) {
            return false
        }
        val socket = synchronized(lock) {
            if (finished) return false
            if (sentTypes.size >= 32) trimSentTypes()
            sentTypes[request.id] = request.payloadCase to System.currentTimeMillis()
            webSocket
        } ?: return false
        if (!socket.send(text)) {
            synchronized(lock) { sentTypes.remove(request.id) }
            return false
        }
        return true
    }

    fun close() {
        val socket = synchronized(lock) {
            finished = true
            sentTypes.clear()
            webSocket.also { webSocket = null }
        }
        socket?.close(1000, null)
    }

    private fun handle(text: String) {
        val response = try {
            WrpcJson.decode(text, ::typeOf, blockDetail)
        } catch (e: Exception) {
            Log.w("WrpcSocket", "Undecodable frame from $url: ${e.javaClass.simpleName}")
            null
        } ?: return
        onMessage(response)
    }

    private fun typeOf(id: Long): Messages.KaspadRequest.PayloadCase? = synchronized(lock) { sentTypes.remove(id)?.first }

    /** With the lock held: forgets requests older than any request's timeout, then the oldest past
     *  the limit. */
    private fun trimSentTypes() {
        val cutoff = System.currentTimeMillis() - SENT_TYPE_MAX_AGE_MS
        sentTypes.entries.removeAll { it.value.second < cutoff }
        val excess = sentTypes.size - SENT_TYPES_LIMIT + 1
        if (excess > 0) {
            val oldest = sentTypes.keys.sorted().take(excess)
            oldest.forEach { sentTypes.remove(it) }
        }
    }

    private fun finish(error: Throwable?) {
        val wasFinished = synchronized(lock) {
            val was = finished
            finished = true
            sentTypes.clear()
            webSocket = null
            was
        }
        if (!wasFinished) onClosed(error)
    }

    internal companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val SENT_TYPES_LIMIT = 256
        const val SENT_TYPE_MAX_AGE_MS = 120_000L

        /** No read timeout and no ping frames: the pool's own GetInfo keeps a connection busy,
         *  and an idle receive is not a dead socket (B11). */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15_000L, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
