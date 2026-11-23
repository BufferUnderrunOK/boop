package net.tileringer.boop

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
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.tileringer.boop.protocol.ProtocolEvent
import net.tileringer.boop.protocol.TileProtocolMachine

private const val TAG = "BoopWatcher"

/**
 * "Kitty watch": holds an authenticated link to every Tile in range so a double-press on the Tile
 * reaches this phone (a Tile can only report button presses to a connected, authenticated app).
 *
 * Everything runs on one thread, so the (non-thread-safe) protocol machines need no locking.
 * Lifecycle is owned by [WatchService].
 */
@SuppressLint("MissingPermission")
object Watcher {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private var scope: CoroutineScope? = null
    private val links = mutableMapOf<String, TileLink>()
    private val connecting = mutableSetOf<String>()

    private val _linked = MutableStateFlow<Set<String>>(emptySet())
    /** Tile ids with a live, authenticated link. */
    val linked: StateFlow<Set<String>> = _linked

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun start(context: Context) {
        if (scope != null) return
        val appContext = context.applicationContext
        val newScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope = newScope
        _running.value = true
        val tiles = TileStore.load(appContext)
        newScope.launch { scanLoop(appContext, tiles, newScope) }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        links.values.forEach { it.session.close() }
        links.clear()
        connecting.clear()
        _linked.value = emptySet()
        _running.value = false
    }

    /** Ring through a held link, or null if Kitty watch isn't linked to that Tile. */
    suspend fun ringThroughLink(tileId: String): RingHandle? = withContext(dispatcher) {
        val link = links[tileId] ?: return@withContext null
        runCatching { link.ring() }.onFailure { Log.w(TAG, "ring via link failed", it) }.getOrNull()
    }

    suspend fun stopRing(tileId: String) = withContext(dispatcher) {
        links[tileId]?.stopSound()
    }

    private suspend fun scanLoop(context: Context, tiles: List<TileInfo>, scope: CoroutineScope) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GattSession.FEED_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scope.launch { onSeen(context, tiles, result.device, scope) }
            }
        }
        // Tiles already linked to this phone by another app (the Tile app) don't advertise, so the
        // scan never sees them. Android shares that link; join it and open our own channel.
        scope.launch {
            val manager = context.getSystemService(BluetoothManager::class.java)
            while (isActive) {
                if (adapter.isEnabled) {
                    runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList())
                        .forEach { onSeen(context, tiles, it, scope) }
                }
                delay(60_000)
            }
        }
        while (scope.isActive) {
            val scanner = adapter.bluetoothLeScanner
            if (adapter.isEnabled && scanner != null) {
                runCatching { scanner.startScan(listOf(filter), settings, callback) }
                // Android quietly downgrades scans older than 30 min, so restart before that.
                delay(25 * 60_000L)
                runCatching { scanner.stopScan(callback) }
            } else {
                delay(30_000)
            }
        }
    }

    private fun onSeen(context: Context, tiles: List<TileInfo>, device: BluetoothDevice, scope: CoroutineScope) {
        val tile = tiles.firstOrNull { it.matchesAddress(device.address) } ?: return
        if (tile.id in links || !connecting.add(tile.id)) return
        scope.launch {
            val session = GattSession(context, device)
            try {
                session.connect()
                val machine = handshake(session, tile)
                val link = TileLink(tile, session, machine)
                links[tile.id] = link
                _linked.update { it + tile.id }
                Log.i(TAG, "linked ${tile.name}")
                connecting.remove(tile.id)
                link.pump(context)
            } catch (e: Throwable) {
                Log.w(TAG, "link to ${tile.name} ended: ${e.message}")
            } finally {
                session.close()
                links.remove(tile.id)
                connecting.remove(tile.id)
                _linked.update { it - tile.id }
            }
        }
    }
}

/** One authenticated, held connection. Only touched on the Watcher's thread. */
private class TileLink(val tile: TileInfo, val session: GattSession, val machine: TileProtocolMachine) {
    private var ringStarted: CompletableDeferred<Unit>? = null
    private var ringEnded = CompletableDeferred<Unit>()

    /** Reads notifications until the link drops: answers keep-alives, reacts to button presses. */
    suspend fun pump(context: Context) {
        while (true) {
            val packet = session.notifications.receiveCatching().getOrNull() ?: return
            // Button presses come on the broadcast channel, which the protocol machine ignores.
            val press = ButtonPacket.parse(packet)
            if (press != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "${tile.name} button ${if (press) "double" else "single"}")
                if (press) onDoublePress(context)
                continue
            }
            if (packet.size >= 2 && packet[0].toInt() == 1) continue // other broadcast traffic
            val events = session.process(machine.receive(packet))
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "${tile.name} <- ${packet.joinToString("") { "%02x".format(it) }} -> ${events.map { it::class.simpleName }}")
            }
            for (event in events) when (event) {
                is ProtocolEvent.ButtonPress -> if (event.double) onDoublePress(context)
                is ProtocolEvent.RingStarted -> ringStarted?.complete(Unit)
                is ProtocolEvent.RingStopped -> ringEnded.complete(Unit)
                else -> Unit
            }
        }
    }

    /** Double-press toggles the yowl. (Singles are ignored: every double-press starts with two.) */
    private fun onDoublePress(context: Context) {
        Log.i(TAG, "${tile.name}: double press")
        if (FindPhoneAlarm.isAlarming) FindPhoneAlarm.stop(context) else FindPhoneAlarm.start(context, tile.name)
    }


    suspend fun ring(): RingHandle {
        val started = CompletableDeferred<Unit>()
        ringStarted = started
        ringEnded = CompletableDeferred()
        session.process(machine.playHigh())
        withTimeout(8_000) { started.await() }
        val ended = ringEnded
        return object : RingHandle {
            override suspend fun awaitEnd(maxMillis: Long) {
                withTimeoutOrNull(maxMillis) { ended.await() }
            }

            override suspend fun stop() {
                runCatching { Watcher.stopRing(tile.id) }
            }

            override fun release() = Unit // the watch link stays up
        }
    }

    suspend fun stopSound() {
        session.process(machine.stop())
    }
}
