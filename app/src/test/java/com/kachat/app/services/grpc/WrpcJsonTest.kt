package com.kachat.app.services.grpc

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import protowire.Messages
import protowire.Rpc

/** The wRPC JSON codec (iOS 961fc05a `WRPCJSON`), against the frames kaspad 2.1.0's public nodes
 *  send. */
class WrpcJsonTest {

    private fun request(build: Messages.KaspadRequest.Builder.() -> Unit): Messages.KaspadRequest =
        Messages.KaspadRequest.newBuilder().apply(build).build()

    @Test
    fun `requests map to their wRPC methods`() {
        val info = WrpcJson.request(request { setId(1); setGetInfoRequest(Rpc.GetInfoRequestMessage.getDefaultInstance()) })
        assertEquals("getInfo", info.first)

        val utxos = WrpcJson.request(request {
            setId(2)
            setGetUtxosByAddressesRequest(Rpc.GetUtxosByAddressesRequestMessage.newBuilder().addAddresses("kaspa:qq").build())
        })
        assertEquals("getUtxosByAddresses", utxos.first)
        assertEquals("kaspa:qq", utxos.second["addresses"].asJsonArray[0].asString)

        val start = WrpcJson.request(request {
            setNotifyBlockAddedRequest(Rpc.NotifyBlockAddedRequestMessage.newBuilder().setCommand(Rpc.RpcNotifyCommand.NOTIFY_START).build())
        })
        assertEquals("subscribe", start.first)
        assertTrue(start.second.has("BlockAdded"))

        val stop = WrpcJson.request(request {
            setNotifyBlockAddedRequest(Rpc.NotifyBlockAddedRequestMessage.newBuilder().setCommand(Rpc.RpcNotifyCommand.NOTIFY_STOP).build())
        })
        assertEquals("unsubscribe", stop.first)
    }

    @Test(expected = WrpcJson.UnsupportedRequestException::class)
    fun `a request K never sends is refused, not sent under a name that closes the socket`() {
        WrpcJson.request(request { setGetBlockCountRequest(Rpc.GetBlockCountRequestMessage.getDefaultInstance()) })
    }

    @Test
    fun `a transaction carries mass and storageMass alike and a versioned script`() {
        val tx = Rpc.RpcTransaction.newBuilder()
            .setVersion(0)
            .addOutputs(
                Rpc.RpcTransactionOutput.newBuilder().setAmount(100_000_000L)
                    .setScriptPublicKey(Rpc.RpcScriptPublicKey.newBuilder().setVersion(0).setScriptPublicKey("20abac"))
            )
            .setStorageMass(42)
            .setPayload("6b")
            .setSubnetworkId("0000000000000000000000000000000000000000")
            .build()
        val json = WrpcJson.transactionJson(tx)
        assertEquals(42L, json["mass"].asLong)
        assertEquals(42L, json["storageMass"].asLong)
        val output = json["outputs"].asJsonArray[0].asJsonObject
        assertEquals("000020abac", output["scriptPublicKey"].asString)
        assertEquals(100_000_000L, output["value"].asLong)
        assertTrue(output["covenant"].isJsonNull)
    }

    @Test
    fun `an answer is matched to its request type by id`() {
        val frame = """{"id":7,"method":"getInfo","params":{"serverVersion":"2.1.0","isSynced":true,"isUtxoIndexed":true}}"""
        val response = WrpcJson.decode(frame, { id -> if (id == 7L) Messages.KaspadRequest.PayloadCase.GETINFOREQUEST else null })!!
        assertEquals(7L, response.id)
        assertEquals("2.1.0", response.getInfoResponse.serverVersion)
        assertTrue(response.getInfoResponse.isSynced)
    }

    @Test
    fun `an answer to nothing sent is dropped`() {
        assertNull(WrpcJson.decode("""{"id":9,"params":{}}""", { null }))
    }

    @Test
    fun `an error answer comes back in its request's shape`() {
        val frame = """{"id":3,"method":"submitTransaction","error":{"code":0,"message":"orphan","data":null}}"""
        val response = WrpcJson.decode(frame, { Messages.KaspadRequest.PayloadCase.SUBMITTRANSACTIONREQUEST })!!
        assertTrue(response.hasSubmitTransactionResponse())
        assertEquals("orphan", response.submitTransactionResponse.error.message)
    }

    @Test
    fun `wRPC's network name is the one gRPC reports`() {
        val frame = """{"id":1,"params":{"network":"testnet-10","virtualDaaScore":"123456789"}}"""
        val response = WrpcJson.decode(frame, { Messages.KaspadRequest.PayloadCase.GETBLOCKDAGINFOREQUEST })!!
        assertEquals("kaspa-testnet-10", response.getBlockDagInfoResponse.networkName)
        // a u64 written as a string reads (C6)
        assertEquals(123456789L, response.getBlockDagInfoResponse.virtualDaaScore)
        assertEquals("kaspa-mainnet", WrpcJson.networkName("kaspa-mainnet"))
    }

    @Test
    fun `utxo entries keep their outpoint, script and covenant`() {
        val frame = """{"id":2,"params":{"entries":[{"address":"kaspa:qq","outpoint":{"transactionId":"ab","index":1},
            "utxoEntry":{"amount":"5000","scriptPublicKey":"000020abac","blockDaaScore":9,"isCoinbase":false,"covenantId":"cc"}}]}}"""
        val entry = WrpcJson.decode(frame, { Messages.KaspadRequest.PayloadCase.GETUTXOSBYADDRESSESREQUEST })!!
            .getUtxosByAddressesResponse.entriesList.single()
        assertEquals("ab", entry.outpoint.transactionId)
        assertEquals(1, entry.outpoint.index)
        assertEquals(5000L, entry.utxoEntry.amount)
        assertEquals("20abac", entry.utxoEntry.scriptPublicKey.scriptPublicKey)
        assertEquals("cc", entry.utxoEntry.covenantId)
    }

    @Test
    fun `peer addresses are written as gRPC writes them`() {
        val frame = """{"id":4,"params":{"knownAddresses":[{"ip":"::ffff:1.2.3.4","port":16111},{"ip":"2001:db8::1","port":16111},{"ip":"","port":1}]}}"""
        val addresses = WrpcJson.decode(frame, { Messages.KaspadRequest.PayloadCase.GETPEERADDRESSESREQUEST })!!
            .getPeerAddressesResponse.addressesList.map { it.addr }
        assertEquals(listOf("1.2.3.4:16111", "[2001:db8::1]:16111"), addresses)
    }

    private val blockFrame = """{"method":"blockAddedNotification","params":{"BlockAdded":{"block":{
        "header":{"hash":"bb","timestamp":1700000000000,"daaScore":10,"blueScore":"9","parentsByLevel":[["p1","p2"]]},
        "verboseData":{"hash":"bb","blueScore":9,"childrenHashes":["c1"],"isChainBlock":true},
        "transactions":[
          {"version":0,"inputs":[],"outputs":[{"value":1,"scriptPublicKey":"0000aa","verboseData":{"scriptPublicKeyAddress":"kaspa:qa"}}],"payload":"","verboseData":{"transactionId":"t0"}},
          {"version":0,"inputs":[],"outputs":[],"payload":"6b","verboseData":{"transactionId":"t1"}}
        ]}}}}"""

    @Test
    fun `a scanned block keeps its header and only its payload transactions`() {
        val block = WrpcJson.decode(blockFrame, { null }, { WrpcJson.BlockDetail.PAYLOADS })!!.blockAddedNotification.block
        assertEquals(1700000000000L, block.header.timestamp)
        assertEquals(listOf("t1"), block.transactionsList.map { it.verboseData.transactionId })
        assertFalse(block.hasVerboseData())
    }

    @Test
    fun `the Explorer's block has every transaction and the verbose data`() {
        val block = WrpcJson.decode(blockFrame, { null }, { WrpcJson.BlockDetail.FULL })!!.blockAddedNotification.block
        assertEquals("bb", block.verboseData.hash)
        assertEquals(9L, block.header.blueScore)
        assertEquals(listOf("p1", "p2"), block.header.parentsList.single().parentHashesList)
        assertEquals(listOf("c1"), block.verboseData.childrenHashesList)
        assertEquals(listOf("t0", "t1"), block.transactionsList.map { it.verboseData.transactionId })
        assertEquals("kaspa:qa", block.transactionsList[0].outputsList[0].verboseData.scriptPublicKeyAddress)
    }

    @Test
    fun `with nothing listening a block is not read`() {
        val block = WrpcJson.decode(blockFrame, { null }, { WrpcJson.BlockDetail.NONE })!!.blockAddedNotification.block
        assertEquals(0, block.transactionsCount)
        assertFalse(block.hasHeader())
    }

    @Test
    fun `bad numbers read as zero instead of crashing`() {
        assertEquals(0L, WrpcJson.u64(JsonParser.parseString("\"x\"")))
        assertEquals(0L, WrpcJson.u64(JsonParser.parseString("-5")))
        assertEquals(12L, WrpcJson.u64(JsonParser.parseString("12.0")))
        // a full uint64 keeps its bits, as protobuf does
        assertEquals(-1L, WrpcJson.u64(JsonParser.parseString("18446744073709551615")))
    }

    @Test
    fun `a request frame carries its id`() {
        val frame = WrpcJson.requestFrame(request { setId(11); setGetInfoRequest(Rpc.GetInfoRequestMessage.getDefaultInstance()) })
        val root = JsonParser.parseString(frame).asJsonObject
        assertEquals(11L, root["id"].asLong)
        assertEquals("getInfo", root["method"].asString)
    }
}
