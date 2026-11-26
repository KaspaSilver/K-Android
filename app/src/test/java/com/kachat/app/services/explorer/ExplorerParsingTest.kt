package com.kachat.app.services.explorer

import com.google.gson.JsonParser
import com.kachat.app.util.KaspaNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Explorer's pure parts (iOS 961fc05a `ExplorerAPI`, `ExplorerKStats`, `ExplorerModels`). */
class ExplorerParsingTest {
    private fun hex(text: String) = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val mainnet = KaspaNetwork.Type.MAINNET

    @Test
    fun `payloads are read as K's protocols`() {
        fun kind(payload: String?, subnetwork: String? = null, covenants: List<String> = emptyList()) =
            ExplorerParsing.classify(payload?.let { hex(it) }, subnetwork, covenants, mainnet).first
        assertEquals(ExplorerTxKind.COINBASE, kind(null, subnetwork = "0100000000000000000000000000000000000000"))
        assertEquals(ExplorerTxKind.PAYMENT, kind(null))
        assertEquals(ExplorerTxKind.PAYMENT, kind(""))
        assertEquals(ExplorerTxKind.HANDSHAKE, kind("kchat:1:handshake:abc"))
        assertEquals(ExplorerTxKind.HANDSHAKE, kind("ciph_msg:1:self_stash:abc"))
        assertEquals(ExplorerTxKind.KACHAT_MESSAGE, kind("kchat:1:comm:alias:data"))
        assertEquals(ExplorerTxKind.KACHAT_MESSAGE, kind("ciph_msg:gcomm:x"))
        assertEquals(ExplorerTxKind.PAYMENT, kind("kchat:1:pay:x"))
        assertEquals(ExplorerTxKind.KACHAT_NAME, kind("kchat:1:name:x"))
        assertEquals(ExplorerTxKind.PUBLIC_CHAT, kind("kchat:1:bcast:general:hello"))
        assertEquals(ExplorerTxKind.CHESS_MOVE, kind("kchat:1:bcast:chess-arena:e4"))
        assertEquals(ExplorerTxKind.CHESS_MOVE, kind("kchat:1:bcast:chess-bots:e4"))
        assertEquals(ExplorerTxKind.ARCADE_STAMP, kind("kchat:1:bcast:kplay-arcade:{}"))
        assertEquals(ExplorerTxKind.KAPOST, kind("kchat:1:post:pub:sig:msg:[]"))
        assertEquals(ExplorerTxKind.KAPOST, kind("k:1:post:pub:sig:msg:[]"))
        assertEquals(ExplorerTxKind.OTHER, kind("someone else's app"))
        // kUSDT's covenants, either case
        val deployment = com.kachat.app.services.tokens.Kusdt.deployment(mainnet)
        assertEquals(ExplorerTxKind.KUSDT_TRANSFER, kind(null, covenants = listOf(deployment.covenantIdHex.uppercase())))
    }

    @Test
    fun `a list reads only the payload's start, its own page the room message`() {
        val payload = hex("kchat:1:bcast:general:hello there")
        assertNull(ExplorerParsing.classify(payload, null, emptyList(), mainnet).second)
        assertEquals("#general: hello there", ExplorerParsing.classify(payload, null, emptyList(), mainnet, full = true).second)
        // not UTF-8 in full: some other app's
        assertEquals(ExplorerTxKind.OTHER, ExplorerParsing.classify("ff", null, emptyList(), mainnet, full = true).first)
        // not hex: nothing to read
        assertEquals(ExplorerTxKind.PAYMENT, ExplorerParsing.classify("zz", null, emptyList(), mainnet).first)
    }

    @Test
    fun `node numbers read as strings, numbers or not at all (audit C6, C11)`() {
        assertEquals(42L, ExplorerParsing.long(JsonParser.parseString("\"42\"")))
        assertEquals(42L, ExplorerParsing.long(JsonParser.parseString("42")))
        assertEquals(42L, ExplorerParsing.long(JsonParser.parseString("42.9")))
        assertEquals(Long.MAX_VALUE, ExplorerParsing.long(JsonParser.parseString("\"18446744073709551615\"")))
        assertEquals(0L, ExplorerParsing.long(JsonParser.parseString("-5")))
        assertNull(ExplorerParsing.long(null))
        assertEquals(0L, ExplorerMath.clampedLong(Double.NaN))
        assertEquals(Long.MAX_VALUE, ExplorerMath.clampedLong(1e300))
        assertEquals(194_457_000L, ExplorerMath.sompi(1.94457))
    }

    @Test
    fun `a node block decodes without its default fields`() {
        val json = JsonParser.parseString(
            """{"header":{"timestamp":"1700000000000","daaScore":"5","blueScore":4,"parents":[{"parentHashes":["p1","p2"]}]},
               "verboseData":{"hash":"h","isChainBlock":true,"transactionIds":["t0","t1"],"childrenHashes":["c"]},
               "transactions":[{"subnetworkId":"0100000000000000000000000000000000000000","outputs":[{"amount":"100","verboseData":{"scriptPublicKeyAddress":"kaspa:a"}}]},
                               {"inputs":[{}],"outputs":[{"amount":5}],"payload":"","verboseData":{"transactionId":"t1"}}]}"""
        ).asJsonObject
        val block = ExplorerParsing.nodeBlock(json, mainnet)!!
        assertEquals("h", block.id)
        assertEquals(5L, block.daaScore)
        assertEquals(listOf("p1", "p2"), block.parents)
        assertEquals(listOf("t0", "t1"), block.transactions.map { it.id })
        assertEquals(ExplorerTxKind.COINBASE, block.transactions[0].kind)
        assertEquals(0L, block.transactions[0].fee)
        assertNull(block.transactions[1].fee)
        assertNull(ExplorerParsing.nodeBlock(JsonParser.parseString("{}").asJsonObject, mainnet))
    }

    @Test
    fun `an indexed transaction knows its fee and confirmations`() {
        val json = JsonParser.parseString(
            """{"transaction_id":"x","mass":"2000","block_hash":["b"],"block_time":1700000000000,"is_accepted":true,
               "accepting_block_blue_score":100,
               "inputs":[{"previous_outpoint_address":"kaspa:me","previous_outpoint_amount":1000}],
               "outputs":[{"amount":300,"script_public_key_address":"kaspa:you"},{"amount":690,"script_public_key_address":"kaspa:me"}]}"""
        ).asJsonObject
        val tx = ExplorerParsing.indexedTransaction(json, virtualBlueScore = 109, network = mainnet)!!
        assertEquals(10L, tx.fee)
        assertEquals(10, tx.confirmations)
        assertEquals(2000L, tx.mass)
        assertEquals("b", tx.blockHash)
        assertEquals(300L, tx.movedAmount)
        assertEquals(false to 310L, tx.net("kaspa:me"))
        assertEquals(true to 300L, tx.net("kaspa:you"))
    }

    @Test
    fun `a node transaction's change can't be told, so it has no amount (audit C4)`() {
        val unknownInputs = listOf(ExplorerIO(0, "", 0))
        val tx = ExplorerTransaction(
            id = "x", kind = ExplorerTxKind.PAYMENT, time = 0, blockHash = "b", confirmations = 0, fee = null, mass = 0,
            inputs = unknownInputs, outputs = listOf(ExplorerIO(0, "kaspa:a", 100), ExplorerIO(1, "kaspa:b", 10_000)),
            payloadHex = "", tokenUnits = null, decoded = null,
        )
        assertNull(tx.movedAmount)
        assertEquals(100L, tx.copy(outputs = listOf(ExplorerIO(0, "kaspa:a", 100))).movedAmount)
        // a self-send moves its first output
        val self = tx.copy(inputs = listOf(ExplorerIO(0, "kaspa:a", 200)), outputs = listOf(ExplorerIO(0, "kaspa:a", 150)))
        assertEquals(150L, self.movedAmount)
    }

    @Test
    fun `routes survive as text`() {
        val routes = listOf(
            ExplorerRoute.Transaction("ab"), ExplorerRoute.Block("cd"), ExplorerRoute.Address("kaspa:qq"),
            ExplorerRoute.Chart(ExplorerChartMetric.NETWORK_FEES),
        )
        for (route in routes) assertEquals(route, ExplorerRoute.decode(route.encode()))
        assertNull(ExplorerRoute.decode("nonsense"))
    }

    @Test
    fun `downsampling keeps both ends`() {
        val points = (0 until 1000).map { ExplorerChartPoint(it.toLong(), it.toDouble()) }
        val down = ExplorerParsing.downsample(points, 240)
        assertEquals(240, down.size)
        assertEquals(0L, down.first().date)
        assertEquals(999L, down.last().date)
        assertEquals(points.take(5), ExplorerParsing.downsample(points.take(5), 240))
    }

    @Test
    fun `an address is found anywhere in pasted text`() {
        val address = "kaspa:qypr7ayn2a0s6ag3vpt9kyk3s4rf9nt6l5e7yxxnqc8wq3f5v4f2rssnvx2lc"
        assertEquals(address, ExplorerParsing.addressIn("send to $address, thanks"))
        assertNull(ExplorerParsing.addressIn("no address here"))
        assertTrue(ExplorerParsing.isHexId("a".repeat(64)))
        assertFalse(ExplorerParsing.isHexId("g".repeat(64)))
    }

    @Test
    fun `K Activity reads stats and the richer activity`() {
        val stats = JsonParser.parseString(
            """{"categories":{"messages":{"total":10,"last24h":2,"last7d":5},"groupMessages":{"total":1,"last24h":1},"selfStash":{"total":99}}}"""
        )
        val read = ExplorerKStatsParsing.stats(stats)
        assertEquals(3, read[ExplorerKPeriod.DAY]!!.counts[ExplorerTxKind.KACHAT_MESSAGE])
        assertEquals(5, read[ExplorerKPeriod.WEEK]!!.counts[ExplorerTxKind.KACHAT_MESSAGE])
        assertEquals(11, read[ExplorerKPeriod.ALL_TIME]!!.total)
        assertNull(read[ExplorerKPeriod.MONTH])

        val rich = JsonParser.parseString(
            """{"categories":{"kaposts":4,"kapostActions":6},"previous":{"kaposts":5},"series":[{"t":2,"count":7},{"t":1,"count":3}]}"""
        )
        val activity = ExplorerKStatsParsing.activity(rich, ExplorerKPeriod.DAY)!!
        assertEquals(10, activity.counts[ExplorerTxKind.KAPOST])
        assertEquals(listOf(3, 7), activity.bars)
        assertEquals(100.0, activity.change!!, 0.001)
        assertNull(ExplorerKStatsParsing.activity(JsonParser.parseString("{}"), ExplorerKPeriod.DAY))
    }

    @Test
    fun `Kaspalytics dates parse with or without fractions (audit C11)`() {
        assertEquals(1_700_000_000_000L, ExplorerKStatsParsing.kaspalyticsDate("2023-11-14T22:13:20.000Z"))
        assertEquals(1_700_000_000_000L, ExplorerKStatsParsing.kaspalyticsDate("2023-11-14T22:13:20Z"))
        assertEquals(1_699_920_000_000L, ExplorerKStatsParsing.kaspalyticsDate("2023-11-14"))
        assertNull(ExplorerKStatsParsing.kaspalyticsDate("yesterday"))
        val chart = JsonParser.parseString(
            """{"labels":["2023-11-14","2023-11-15","2023-12-01"],"datasets":[{"label":"Fees","data":[1.5,null,2]},{"label":"Other","data":[9,9,9]}]}"""
        )
        val series = ExplorerKStatsParsing.chartSeries(chart, "Fees")
        assertEquals(listOf(1.5, 2.0), series.map { it.value })
        assertEquals(2.0, ExplorerKStatsParsing.lastValue(chart, "Fees")!!, 0.0)
        assertEquals(listOf(1.5, 2.0), ExplorerKStatsParsing.monthly(series).map { it.value })
    }

    @Test
    fun `the emission schedule steps down`() {
        // before the chromatic phase: 500 a second, one block a second
        assertEquals(500.0, ExplorerDemo.rewardPerBlock(ExplorerDemo.MAINNET_LAUNCH), 0.0)
        // after Crescendo a block is a tenth of the second's
        val after = ExplorerDemo.rewardPerBlock(ExplorerDemo.CRESCENDO + 1)
        val before = ExplorerDemo.rewardPerBlock(ExplorerDemo.CRESCENDO - 1)
        assertEquals(before / 10, after, before * 0.01)
        // the demo is the same every time for the same window
        assertEquals(
            ExplorerDemo.kActivity(ExplorerKPeriod.WEEK, ExplorerDemo.launchedAt).bars,
            ExplorerDemo.kActivity(ExplorerKPeriod.WEEK, ExplorerDemo.launchedAt).bars,
        )
    }
}
