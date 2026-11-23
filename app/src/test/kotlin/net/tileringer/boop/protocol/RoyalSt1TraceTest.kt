package net.tileringer.boop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Replays a real session with a ROYAL_ST1 (firmware 48.04.28.0), captured from Boop's debug log on
 * 2026-11-22, through the protocol machine and checks every frame Boop sent, byte for byte.
 *
 * The packets are safe to publish: without the Tile's auth key they reveal nothing. The key is
 * not in the repo, so this test is skipped unless BOOP_TRACE_AUTH_KEY holds it (base64), e.g.
 *   BOOP_TRACE_AUTH_KEY=... ./gradlew testDebugUnitTest
 * Pattern from tile-wear's RealProTraceTest.
 */
class RoyalSt1TraceTest {
    @Test
    fun `ROYAL_ST1 session replays byte for byte`() {
        val authKey = System.getenv("BOOP_TRACE_AUTH_KEY")
        assumeTrue("BOOP_TRACE_AUTH_KEY not set", !authKey.isNullOrBlank())
        val machine = TileProtocolMachine(
            authKeyBase64 = authKey,
            expectedTdtConfig = "DFUjMg==",
            randA = bytes("fecf3859cb641e9928a9a7a68c52"),
            mepId = bytes("78723b11"),
            unixTimeSeconds = { 1_795_000_000 },
        )

        // Connectionless: Tile Data Information, then authenticate and open a channel.
        assertWrite(machine.start(), "0078723b111301")
        assertWrite(machine.receive(bytes("0078723b1114013f")), "0078723b111302")
        assertWrite(machine.receive(bytes("0078723b1114021cea80dd612aa3cf")), "0078723b111303")
        assertWrite(machine.receive(bytes("0078723b11140334382e30342e32382e30")), "0078723b111304")
        assertWrite(machine.receive(bytes("0078723b11140454494c452032342e3030")), "0078723b111305")
        val info = machine.receive(bytes("0078723b11140532342e3030"))
        assertTrue(info.any { it is ProtocolEvent.TileInfo && it.id == "1cea80dd612aa3cf" })
        assertWrite(info, "0078723b1114fecf3859cb641e9928a9a7a68c52")
        assertWrite(machine.receive(bytes("0078723b11152c87f087492b094649a8ed3877f1")), "0078723b1110fecf3859cb641e9928a9a7a68c52")
        assertWrite(machine.receive(bytes("0078723b11120372536309944cd569faef4e919c")), "03121355341170")

        // Channel 3: READY, diagnostics, connection tuning, double-tap config, song map.
        assertWrite(machine.receive(bytes("03010e2dde220b00000003d782b0f7")), "030a01fc2a5858")
        machine.receive(bytes("030c02550260180e131100004e0100a1cd7c7632"))
        machine.receive(bytes("030c0296000d4c01002f01002e01000108f197ce"))
        machine.receive(bytes("030c020022000000070007001f000000c272a5c6"))
        machine.receive(bytes("030c020005bbbc8300becc000000000066df82d5"))
        machine.receive(bytes("030c020000000000000000000000003c6586dd37"))
        assertWrite(machine.receive(bytes("030c01004ff9455a")), "03070275e22bb1")
        assertWrite(machine.receive(bytes("030902601896509d5e")), "030c032001300104005802b8a7234c")
        val setup = machine.receive(bytes("030e032001300104005802ddead4ab")).filterIsInstance<ProtocolEvent.Write>()
        assertEquals(listOf("0304010c55233224feda3a", "03050675d0d3a3"), setup.map { it.bytes.hex() })
        // A keep-alive arrives mid-setup and is answered.
        assertWrite(machine.receive(bytes("030a03ca84f49c")), "030803f7644070")
        assertEquals(emptyList<ProtocolEvent>(), machine.receive(bytes("0306010c552332d5eaf754")))
        assertTrue(machine.receive(bytes("0307070100004399d959")).any { it is ProtocolEvent.Authenticated })
        assertEquals(TileProtocolMachine.State.READY, machine.state())
    }

    private fun assertWrite(events: List<ProtocolEvent>, expected: String) {
        assertEquals(expected, events.filterIsInstance<ProtocolEvent.Write>().single().bytes.hex())
    }

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
