// Ported from colonelpanic8/tile-wear @fa914c9 shared/src/test/.../TileProtocolTest.kt (MIT, Copyright (c) 2026 Ivan Malison).
// Changes: package; dropped the TileSyncCodec test (tile-wear's watch-sync model, not in Boop).
package net.tileringer.boop.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TileProtocolTest {
    private val authKey = bytes("000102030405060708090a0b0c0d0e0f")
    private val randA = bytes("101112131415161718191a1b1c1d")
    private val mepId = bytes("aabbccdd")
    private val channelData = bytes("202122232425262728292a2b2c")

    @Test
    fun `HMAC matches node-tile known answer`() {
        assertEquals(
            "a059eba8b3e48dd95e62406f29553607c9eea9e14ed3d4aacc0bdb532f145e19",
            TileCrypto.hmac(authKey, bytes("010203"), 4).hex(),
        )
    }

    @Test
    fun `channel derivation and open frame match node-tile`() {
        val machine = machine()
        machine.start()
        completeTdi(machine)
        machine.receive(bytes("00aabbccdd1500" + "30".repeat(14)))
        val events = machine.receive(byteArrayOf(0) + mepId + byteArrayOf(18, 0x42) + channelData)
        val frame = events.filterIsInstance<ProtocolEvent.Write>().single().bytes
        assertEquals("4212132c682cb9", frame.hex())
    }

    @Test
    fun `READY MIC is verified and setup frames match node-tile`() {
        val machine = openMachine()
        val ready = bytes("42010e2dde2200000000131b883db2")
        val events = machine.receive(ready)
        assertEquals("420a01450449b3", events.filterIsInstance<ProtocolEvent.Write>().single().bytes.hex())

        val corrupted = openMachine().receive(ready.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertTrue(corrupted.single() is ProtocolEvent.Failure)
    }

    @Test
    fun `parses TDI and SONG responses`() {
        val machine = machine()
        assertEquals("00aabbccdd1301", machine.start().filterIsInstance<ProtocolEvent.Write>().single().bytes.hex())
        assertEquals("00aabbccdd1302", write(machine.receive(bytes("00aabbccdd14010f"))).hex())
        assertEquals("00aabbccdd1303", write(machine.receive(bytes("00aabbccdd14021122334455667788"))).hex())
        assertEquals("00aabbccdd1304", write(machine.receive(bytes("00aabbccdd140336312e30"))).hex())
        assertEquals("00aabbccdd1305", write(machine.receive(bytes("00aabbccdd140454494c45"))).hex())
        val authEvents = machine.receive(bytes("00aabbccdd1405312e30"))
        assertTrue(authEvents.any { it is ProtocolEvent.TileInfo && it.id == "1122334455667788" })

        val readyMachine = openMachine()
        readyMachine.receive(bytes("42010e0000000000000000cfd21dc3"))
        assertEquals(TileProtocolMachine.State.READY, readyMachine.state())
        val songFrame = readyMachine.playHigh().filterIsInstance<ProtocolEvent.Write>().single().bytes
        val responsePayload = byteArrayOf(7, 2)
        val responseMic = TileCrypto.hmac(
            derivedKey(),
            TileCrypto.longLittleEndian(2),
            0,
            responsePayload.size,
            responsePayload,
        ).copyOf(4)
        val songEvents = readyMachine.receive(byteArrayOf(0x42) + responsePayload + responseMic)
        assertEquals(listOf(ProtocolEvent.RingStarted), songEvents)
        assertTrue(songFrame.isNotEmpty())
    }


    private fun openMachine(): TileProtocolMachine {
        val machine = machine()
        machine.start()
        completeTdi(machine)
        machine.receive(bytes("00aabbccdd1500" + "30".repeat(14)))
        machine.receive(byteArrayOf(0) + mepId + byteArrayOf(18, 0x42) + channelData)
        return machine
    }

    private fun completeTdi(machine: TileProtocolMachine) {
        machine.receive(bytes("00aabbccdd140100"))
    }

    private fun machine() = TileProtocolMachine(
        authKeyBase64 = Base64.getEncoder().encodeToString(authKey),
        randA = randA,
        mepId = mepId,
        unixTimeSeconds = { 1_700_000_000 },
    )

    private fun derivedKey() = bytes("f9187537ac15864c6ca94cbdd35c7fd9")
    private fun write(events: List<ProtocolEvent>) = events.filterIsInstance<ProtocolEvent.Write>().single().bytes
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
