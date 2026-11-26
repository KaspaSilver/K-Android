package com.kachat.app.services.grpc

import com.kachat.app.services.ForegroundBeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** wss node addresses, the public resolver's answers, wss seed pruning, stand-in ranking and the
 *  foreground beat (iOS 961fc05a). */
class NodeTransportTest {

    @Test
    fun `a wss node parses with its path and default port`() {
        val parsed = parseNodeAddress("wss://eric.kaspa.stream/kaspa/mainnet/wrpc/json")!!
        assertTrue(parsed.webSocket)
        assertTrue(parsed.secure)
        assertEquals(443, parsed.port)
        assertEquals("/kaspa/mainnet/wrpc/json", parsed.path)
        assertEquals("wss://eric.kaspa.stream:443/kaspa/mainnet/wrpc/json", parsed.key)
    }

    @Test
    fun `a resolver's Borsh path is taken as its JSON sibling`() {
        assertEquals("/v2/kaspa/mainnet/tls/wrpc/json", parseNodeAddress("wss://x.kaspa.red/v2/kaspa/mainnet/tls/wrpc/borsh")!!.path)
    }

    @Test
    fun `ws is plaintext on port 80, and gRPC addresses are unchanged`() {
        val ws = parseNodeAddress("ws://10.0.0.2:17110")!!
        assertFalse(ws.secure)
        assertEquals(17110, ws.port)
        assertEquals("", ws.path)
        assertEquals(80, parseNodeAddress("ws://node.local")!!.port)
        val grpc = parseNodeAddress("grpcs://node.kaspium.io")!!
        assertFalse(grpc.webSocket)
        assertEquals("node.kaspium.io:443", grpc.key)
        assertNull(parseNodeAddress("wss://"))
    }

    @Test
    fun `the default node is recognised however it is written`() {
        assertTrue(isSameNode("node.kaspium.io:443", "grpcs://node.kaspium.io"))
        assertTrue(isSameNode("grpcs://NODE.kaspium.io:443", "grpcs://node.kaspium.io"))
        assertFalse(isSameNode("grpcs://other.io", "grpcs://node.kaspium.io"))
    }

    @Test
    fun `a resolver answer is taken only for a public node on the network's own domains`() {
        val good = """{"url":"wss://maxim-1.kaspa.stream/v2/kaspa/mainnet/tls/wrpc/borsh"}"""
        assertEquals("wss://maxim-1.kaspa.stream:443/v2/kaspa/mainnet/tls/wrpc/json", PublicNodeResolver.parseAnswer(good, "mainnet"))
        assertNull(PublicNodeResolver.parseAnswer("""{"url":"wss://evil.example.com/v2/kaspa/mainnet/tls/wrpc/borsh"}""", "mainnet"))
        assertNull(PublicNodeResolver.parseAnswer("""{"url":"ws://a.kaspa.stream/v2/kaspa/mainnet/tls/wrpc/borsh"}""", "mainnet"))
        assertNull(PublicNodeResolver.parseAnswer(good, "testnet-10"))
        assertNull(PublicNodeResolver.parseAnswer("""{"url":"wss://a.b.kaspa.stream/v2/kaspa/mainnet/tls/wrpc/borsh"}""", "mainnet"))
        assertNull(PublicNodeResolver.parseAnswer("not json", "mainnet"))
    }

    @Test
    fun `a wss seed with no answer in a day is pruned, a live or new one stays`() {
        val day = WssSeedStore.STALE_AFTER_MS
        val now = 10 * day
        val seeds = listOf(
            WssSeedStore.Seed("wss://dead", firstSeenMs = now - 2 * day, lastSuccessMs = now - 2 * day),
            WssSeedStore.Seed("wss://never", firstSeenMs = now - 2 * day),
            WssSeedStore.Seed("wss://live", firstSeenMs = now - 2 * day, lastSuccessMs = now - 1000),
            WssSeedStore.Seed("wss://new", firstSeenMs = now - 1000),
        )
        val (removed, live) = WssSeedStore.pruneSeeds(seeds, day, now)
        assertEquals(setOf("wss://dead", "wss://never"), removed.toSet())
        assertEquals(setOf("wss://live", "wss://new"), live.toSet())
    }

    private fun reachable(address: String, latency: Long) = NodeProbeResult(
        address = address, reachable = true, latencyMs = latency, isSynced = true,
        isUtxoIndexed = true, serverVersion = "1", networkName = "kaspa-mainnet", virtualDaaScore = null
    )

    @Test
    fun `a wss node ranks below a slower gRPC node, and a public stand-in below every own node`() {
        val registry = NodeRegistry()
        registry.update("1.2.3.4:16110", "Seed", reachable("1.2.3.4:16110", 200))
        registry.update("wss://a.kaspa.stream:443/p", "Seed", reachable("wss://a.kaspa.stream:443/p", 120))
        assertTrue(registry.addPublicFallback("wss://b.kaspa.red:443/p"))
        assertFalse(registry.addPublicFallback("wss://b.kaspa.red:443/p"))
        registry.update("wss://b.kaspa.red:443/p", NodeRegistry.TYPE_PUBLIC, reachable("wss://b.kaspa.red:443/p", 10))
        assertEquals(
            listOf("1.2.3.4:16110", "wss://a.kaspa.stream:443/p", "wss://b.kaspa.red:443/p"),
            registry.activeBestFirst().map { it.address }
        )
    }

    @Test
    fun `a stand-in that never answered or stopped answering is dead after its grace`() {
        val registry = NodeRegistry()
        val t0 = 1_000_000L
        registry.addPublicFallback("wss://never", nowMs = t0)
        registry.addPublicFallback("wss://idle", nowMs = t0)
        registry.update("wss://idle", NodeRegistry.TYPE_PUBLIC, reachable("wss://idle", 50), nowMs = t0 + 1000)
        registry.addPublicFallback("wss://failing", nowMs = t0)
        registry.update("wss://failing", NodeRegistry.TYPE_PUBLIC, reachable("wss://failing", 50), nowMs = t0 + 1000)
        registry.update(
            "wss://failing", NodeRegistry.TYPE_PUBLIC,
            NodeProbeResult("wss://failing", false, null, null, null, null, null, null),
            nowMs = t0 + 2000
        )
        val dead = registry.removeDeadPublicFallbacks(graceMs = 60_000L, nowMs = t0 + 120_000L)
        assertEquals(setOf("wss://never", "wss://failing"), dead.toSet())
        assertEquals(listOf("wss://idle"), registry.publicFallbacks().map { it.address })
    }

    @Test
    fun `own-node connectivity leaves the stand-ins out`() {
        val registry = NodeRegistry()
        val now = 5_000_000L
        registry.update("1.2.3.4:16110", "Seed", NodeProbeResult("1.2.3.4:16110", false, null, null, null, null, null, null), nowMs = now - 10_000)
        registry.addPublicFallback("wss://p", nowMs = now)
        registry.update("wss://p", NodeRegistry.TYPE_PUBLIC, reachable("wss://p", 10), nowMs = now)
        val (lastSuccess, failures) = registry.ownNodeConnectivity(60_000L, now)
        assertNull(lastSuccess)
        assertEquals(1, failures)
    }

    @Test
    fun `the foreground beat lands on the next whole interval plus the offset`() {
        assertEquals(20_000L, ForegroundBeat.delayToNextBeat(30_000L, 0L, 70_000L))
        assertEquals(20_500L, ForegroundBeat.delayToNextBeat(30_000L, 500L, 70_000L))
        // under a second to go: the beat after
        assertEquals(30_500L, ForegroundBeat.delayToNextBeat(30_000L, 0L, 89_500L))
        assertEquals(50_000L, ForegroundBeat.delayToNextBeat(60_000L, 0L, 70_000L))
    }
}
