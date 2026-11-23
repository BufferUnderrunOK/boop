package net.tileringer.boop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonPacketTest {
    // Captured from a ROYAL_ST1 on 2026-11-22: one double-press sends single, single, double.
    private val capturedDoublePress = listOf("010602002a5e90a1", "0106020088cc9287", "010602026632da38")

    @Test
    fun `a captured double-press parses as single, single, double`() {
        assertEquals(listOf(false, false, true), capturedDoublePress.map { ButtonPacket.parse(bytes(it)) })
    }

    @Test
    fun `channel traffic, other broadcast messages and runts are not button presses`() {
        assertNull(ButtonPacket.parse(bytes("030a03ca84f49c")))      // keep-alive on our channel
        assertNull(ButtonPacket.parse(bytes("0306010c552332d5eaf754"))) // TDT config ack on our channel
        assertNull(ButtonPacket.parse(bytes("010601002a5e90a1")))      // broadcast TDT, not a notify
        assertNull(ButtonPacket.parse(bytes("010602052a5e90a1")))      // unknown press code
        assertNull(ButtonPacket.parse(bytes("010602")))
        assertNull(ButtonPacket.parse(ByteArray(0)))
    }

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
