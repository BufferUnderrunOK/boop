package net.tileringer.boop

/**
 * Tile button presses arrive on the Tile's broadcast channel (CID 1), not on our authenticated
 * channel: `01 | 06 (TDT) | 02 (notify) | 00 single / 02 double | MIC[4]`. Captured live from a
 * ROYAL_ST1 (2026-11-22): a double-press sends 00, 00, 02. The MIC is not verified (the broadcast
 * key scheme is undocumented); the worst a spoofer can do is make the phone yowl.
 */
object ButtonPacket {
    private const val BROADCAST_CID = 1
    private const val TDT = 6
    private const val TDT_NOTIFY = 2

    /** true for a double press, false for a single press, null if this isn't a button packet. */
    fun parse(packet: ByteArray): Boolean? {
        if (packet.size < 4) return null
        if (packet[0].toInt() != BROADCAST_CID || packet[1].toInt() != TDT || packet[2].toInt() != TDT_NOTIFY) return null
        return when (packet[3].toInt()) {
            0x02 -> true
            0x00 -> false
            else -> null
        }
    }
}
