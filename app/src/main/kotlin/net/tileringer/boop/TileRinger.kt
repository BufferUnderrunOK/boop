package net.tileringer.boop

// Scan/match/ring flow. Protocol handling is tile-wear's TileProtocolMachine (MIT, see protocol/);
// the event pump mirrors tile-wear wear/.../TileBleClient.kt @fa914c9.

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.tileringer.boop.protocol.ProtocolEvent
import net.tileringer.boop.protocol.TileProtocolMachine
import java.security.SecureRandom

/** A Tile that is currently playing its song because we asked it to. */
interface RingHandle {
    /** Suspends until the song ends on its own, the link drops, or [maxMillis] passes. */
    suspend fun awaitEnd(maxMillis: Long)
    suspend fun stop()
    /** Done with this ring. One-shot connections close; a held watch link stays up. */
    fun release()
}

const val WRITE_GAP_MS = 65L

/** Writes each Write event (with tile-wear's 65 ms gap); throws on Failure. Returns the events. */
suspend fun GattSession.process(events: List<ProtocolEvent>): List<ProtocolEvent> {
    events.forEach { event ->
        when (event) {
            is ProtocolEvent.Write -> { write(event.bytes); delay(WRITE_GAP_MS) }
            is ProtocolEvent.Failure -> error(event.message)
            else -> Unit
        }
    }
    return events
}

/** Full TOA handshake (TDI, auth, channel, setup incl. double-tap config) until the Tile is READY. */
suspend fun handshake(session: GattSession, tile: TileInfo): TileProtocolMachine {
    val random = SecureRandom()
    val machine = TileProtocolMachine(
        authKeyBase64 = tile.authKey,
        expectedTdtConfig = tile.expectedTdtConfig,
        randA = ByteArray(14).also(random::nextBytes),
        mepId = ByteArray(4).also(random::nextBytes),
    )
    session.process(machine.start())
    withTimeout(30_000) {
        while (machine.state() != TileProtocolMachine.State.READY) {
            val packet = session.notifications.receive()
            val events = session.process(machine.receive(packet))
            if (BuildConfig.DEBUG) {
                android.util.Log.d("BoopHandshake", "${tile.name} <- ${packet.joinToString("") { "%02x".format(it) }} -> " +
                    events.joinToString { if (it is ProtocolEvent.Write) "W:" + it.bytes.joinToString("") { b -> "%02x".format(b) } else it::class.simpleName ?: "?" })
            }
        }
    }
    return machine
}

/** One-shot connection made just to ring; closed afterwards. */
private class OneShotRing(private val session: GattSession, private val machine: TileProtocolMachine) : RingHandle {
    override suspend fun awaitEnd(maxMillis: Long) {
        withTimeoutOrNull(maxMillis) {
            while (true) {
                val packet = session.notifications.receiveCatching().getOrNull() ?: break
                val events = runCatching { session.process(machine.receive(packet)) }.getOrNull() ?: break
                if (events.any { it is ProtocolEvent.RingStopped }) break
            }
        }
    }

    override suspend fun stop() {
        runCatching { session.process(machine.stop()); delay(300) }
        release()
    }

    override fun release() = session.close()
}

@SuppressLint("MissingPermission")
class TileRinger(private val context: Context) {

    suspend fun ring(target: TileInfo, allTiles: List<TileInfo>, progress: (String) -> Unit): RingHandle {
        // Kitty watch already holds a link to this Tile: ring through it (a linked Tile doesn't advertise).
        Watcher.ringThroughLink(target.id)?.let { return it }

        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter ?: error("This phone has no Bluetooth")
        check(adapter.isEnabled) { "Bluetooth is off" }

        // A Tile already linked to this phone (e.g. by the Tile/Life360 app) stops advertising,
        // so try those first. Android shares the link between apps.
        manager.getConnectedDevices(BluetoothProfile.GATT)
            .firstOrNull { target.matchesAddress(it.address) }
            ?.let { device ->
                progress("Already connected, saying hi")
                tryRing(device, target, progress)?.let { return it }
            }

        val scanner = adapter.bluetoothLeScanner ?: error("Bluetooth scanner unavailable")
        val results = Channel<ScanResult>(Channel.UNLIMITED)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { results.trySend(result) }
            override fun onBatchScanResults(batch: MutableList<ScanResult>) { batch.forEach { results.trySend(it) } }
            override fun onScanFailed(errorCode: Int) { results.close(IllegalStateException("Bluetooth scan failed ($errorCode)")) }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GattSession.FEED_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val tried = mutableSetOf<String>()

        progress("Looking for ${target.name}…")
        scanner.startScan(listOf(filter), settings, callback)
        try {
            return withTimeout(SCAN_TIMEOUT_MS) {
                while (true) {
                    // Kitty watch may link the Tile mid-scan (a linked Tile stops advertising, so
                    // the scan would never see it): every 2 s, check for a link and ring through it.
                    val result = withTimeoutOrNull(2_000) { results.receive() }
                    if (result == null) {
                        Watcher.ringThroughLink(target.id)?.let { return@withTimeout it }
                        continue
                    }
                    val device = result.device
                    if (!tried.add(device.address)) continue
                    val isTarget = target.matchesAddress(device.address)
                    // Static-address Tiles that aren't the target: skip without connecting.
                    if (!isTarget && allTiles.any { it.matchesAddress(device.address) }) continue
                    scanner.stopScan(callback)
                    val ringing = tryRing(device, target, progress, mustReadId = !isTarget)
                    if (ringing != null) return@withTimeout ringing
                    progress("Still looking for ${target.name}…")
                    scanner.startScan(listOf(filter), settings, callback)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            error("Couldn't find ${target.name}. Is it nearby? (If another phone is connected to it, it hides.)")
        } finally {
            runCatching { scanner.stopScan(callback) }
            results.close()
        }
    }

    /** Connect; if the address didn't identify it, read its ID. Returns null if it's not the target. */
    private suspend fun tryRing(device: BluetoothDevice, target: TileInfo, progress: (String) -> Unit, mustReadId: Boolean = false): RingHandle? {
        val session = GattSession(context, device)
        try {
            progress("Connecting…")
            session.connect()
            if (mustReadId) {
                val id = session.readTileId()
                if (id == null || !id.equals(target.id, ignoreCase = true)) {
                    session.close()
                    return null
                }
            }
            progress("Saying the secret password…")
            val machine = handshake(session, target)
            session.process(machine.playHigh())
            withTimeout(8_000) {
                while (true) {
                    if (session.process(machine.receive(session.notifications.receive())).any { it is ProtocolEvent.RingStarted }) break
                }
            }
            return OneShotRing(session, machine)
        } catch (e: Throwable) {
            session.close()
            if (mustReadId && e !is TimeoutCancellationException) return null
            throw e
        }
    }

    companion object {
        const val SCAN_TIMEOUT_MS = 45_000L
    }
}
