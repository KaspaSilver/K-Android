package com.kachat.app.services.grpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only a plain host:port another node gossips is kept: gRPC throws building a channel for a
 *  malformed target, and any node can send one. */
class GossipedNodeAddressTest {

    @Test
    fun `well-formed peer addresses are kept`() {
        assertTrue(isGossipedNodeAddress("1.2.3.4:16110"))
        assertTrue(isGossipedNodeAddress("[2001:db8::1]:16110"))
        assertTrue(isGossipedNodeAddress("node-1.example.org:443"))
    }

    @Test
    fun `malformed peer addresses are dropped`() {
        assertFalse(isGossipedNodeAddress("a b:1"))
        assertFalse(isGossipedNodeAddress("2001:db8::1:16110"))
        assertFalse(isGossipedNodeAddress("1.2.3.4"))
        assertFalse(isGossipedNodeAddress("1.2.3.4:0"))
        assertFalse(isGossipedNodeAddress("1.2.3.4:70000"))
        assertFalse(isGossipedNodeAddress("1.2.3.4:+80"))
        assertFalse(isGossipedNodeAddress(":16110"))
        assertFalse(isGossipedNodeAddress("[zz]:16110"))
        assertFalse(isGossipedNodeAddress("host/path:80"))
        assertFalse(isGossipedNodeAddress("grpc://1.2.3.4:16110"))
        assertFalse(isGossipedNodeAddress(""))
    }

    @Test
    fun `a gossiped P2P port is tried on the gRPC port, other ports are skipped (audit 2026-11-25 N8)`() {
        assertEquals("1.2.3.4:16110", gossipedGrpcCandidate("1.2.3.4:16111", 16110))
        assertEquals("1.2.3.4:16110", gossipedGrpcCandidate("1.2.3.4:16110", 16110))
        assertEquals("[2001:db8::1]:16110", gossipedGrpcCandidate("[2001:db8::1]:16111", 16110))
        assertEquals("5.6.7.8:16210", gossipedGrpcCandidate("5.6.7.8:16211", 16210))
        assertNull(gossipedGrpcCandidate("1.2.3.4:443", 16110))
        assertNull(gossipedGrpcCandidate("1.2.3.4:16112", 16110))
        assertNull(gossipedGrpcCandidate("a b:16111", 16110))
    }

    @Test
    fun `a manual wss node is one key however it is typed (audit 2026-11-25 T7)`() {
        val a = parseNodeAddress("wss://n.example")?.key
        val b = parseNodeAddress("wss://n.example:443/wrpc/borsh")?.key
        val c = parseNodeAddress("wss://n.example:443/wrpc/json")?.key
        assertEquals(parseNodeAddress("wss://n.example:443")?.key, a)
        assertEquals(b, c)
    }
}
