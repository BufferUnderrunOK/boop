package net.tileringer.boop

// Adapted from colonelpanic8/tile-wear @fa914c9 wear/.../GattSession.kt (MIT, Copyright (c) 2026 Ivan Malison).
// Change: the 9d410007 Tile ID characteristic is optional. Older Tiles (e.g. a ROYAL_ST1)
// don't have it; they're identified by address instead.

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.UUID

@SuppressLint("MissingPermission")
class GattSession(private val context: Context, val device: BluetoothDevice) {
    val notifications = Channel<ByteArray>(Channel.UNLIMITED)

    private val connected = CompletableDeferred<Unit>()
    private val servicesReady = CompletableDeferred<Unit>()
    private var readResult = CompletableDeferred<ByteArray>()
    private var descriptorResult = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null
    private lateinit var command: BluetoothGattCharacteristic
    private lateinit var response: BluetoothGattCharacteristic
    private var idCharacteristic: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
                if (!gatt.discoverServices()) servicesReady.completeExceptionally(IllegalStateException("Service discovery did not start"))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = IllegalStateException("Tile disconnected (status $status)")
                if (!connected.isCompleted) connected.completeExceptionally(error)
                if (!servicesReady.isCompleted) servicesReady.completeExceptionally(error)
                notifications.close(error)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesReady.complete(Unit)
            else servicesReady.completeExceptionally(IllegalStateException("Service discovery failed ($status)"))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (characteristic.uuid != TILE_ID_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) readResult.complete(value.copyOf())
            else readResult.completeExceptionally(IllegalStateException("Tile ID read failed ($status)"))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CLIENT_CONFIG_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) descriptorResult.complete(Unit)
            else descriptorResult.completeExceptionally(IllegalStateException("Notification setup failed ($status)"))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == MEP_RESPONSE_UUID) notifications.trySend(value.copyOf())
        }
    }

    /** Connects, discovers the FEED service and turns on notifications. */
    suspend fun connect() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(20_000) { connected.await() }
        withTimeout(12_000) { servicesReady.await() }
        val service = gatt?.getService(FEED_UUID) ?: error("Not a Tile (no FEED service)")
        command = service.getCharacteristic(MEP_COMMAND_UUID) ?: error("Tile command characteristic missing")
        response = service.getCharacteristic(MEP_RESPONSE_UUID) ?: error("Tile response characteristic missing")
        idCharacteristic = service.getCharacteristic(TILE_ID_UUID)
        enableNotifications()
    }

    /** Tile ID from the 9d410007 characteristic, or null on Tiles that don't have it. */
    suspend fun readTileId(): String? {
        val characteristic = idCharacteristic ?: return null
        readResult = CompletableDeferred()
        check(gatt?.readCharacteristic(characteristic) == true) { "Tile ID read did not start" }
        return withTimeout(6_000) { readResult.await() }.joinToString("") { "%02x".format(it) }
    }

    fun write(bytes: ByteArray) {
        val result = (gatt ?: error("Not connected"))
            .writeCharacteristic(command, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        check(result == BluetoothStatusCodes.SUCCESS) { "Bluetooth write failed ($result)" }
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        notifications.close()
    }

    private suspend fun enableNotifications() {
        val currentGatt = gatt ?: error("Not connected")
        check(currentGatt.setCharacteristicNotification(response, true)) { "Could not enable Tile notifications" }
        val descriptor = response.getDescriptor(CLIENT_CONFIG_UUID) ?: error("Tile notification descriptor is missing")
        descriptorResult = CompletableDeferred()
        val status = currentGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        check(status == BluetoothStatusCodes.SUCCESS) { "Notification setup did not start ($status)" }
        withTimeout(6_000) { descriptorResult.await() }
    }

    companion object {
        val FEED_UUID: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")
        val TILE_ID_UUID: UUID = UUID.fromString("9d410007-35d6-f4dd-ba60-e7bd8dc491c0")
        val MEP_COMMAND_UUID: UUID = UUID.fromString("9d410018-35d6-f4dd-ba60-e7bd8dc491c0")
        val MEP_RESPONSE_UUID: UUID = UUID.fromString("9d410019-35d6-f4dd-ba60-e7bd8dc491c0")
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
