// Copied from colonelpanic8/tile-wear @fa914c9 (MIT License, Copyright (c) 2026 Ivan Malison).
// https://github.com/colonelpanic8/tile-wear
// Boop changes: package line; ButtonPress event for TDT (response 6) notifications, per
// lesleyxyz/node-tile AbstractTileService.ts handleTdtTranscation (prefix 2, data 0x00 single / 0x02 double).
package net.tileringer.boop.protocol

import java.util.Base64

sealed interface ProtocolEvent {
    data class Write(val bytes: ByteArray) : ProtocolEvent
    data class Progress(val message: String) : ProtocolEvent
    data class TileInfo(val id: String?, val firmware: String?, val model: String?, val hardware: String?) : ProtocolEvent
    data object Authenticated : ProtocolEvent
    data object RingStarted : ProtocolEvent
    data object RingStopped : ProtocolEvent
    data class ButtonPress(val double: Boolean) : ProtocolEvent
    data class Failure(val message: String) : ProtocolEvent
}

class TileProtocolMachine(
    authKeyBase64: String,
    private val expectedTdtConfig: String = "",
    private val randA: ByteArray,
    private val mepId: ByteArray,
    private val unixTimeSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    enum class State { NEW, TDI, AUTH, CHANNEL, SETUP, READY, FAILED }

    private val authKey = Base64.getDecoder().decode(authKeyBase64)
    private var state = State.NEW
    private var tdiFeatures = 0
    private var tdiRequest = TDI_FEATURES
    private var tileId: String? = null
    private var firmware: String? = null
    private var model: String? = null
    private var hardware: String? = null
    private var channelPrefix = -1
    private var channelKey = ByteArray(0)
    private var features = ByteArray(0)
    private var nonceA = 0L
    private var nonceT = 0L
    private var waitingForSongMap = false
    private var setupComplete = false

    init {
        require(authKey.size == 16) { "Tile auth key must be 16 bytes" }
        require(randA.size == 14) { "RandA must be 14 bytes" }
        require(mepId.size == 4) { "MEP id must be 4 bytes" }
    }

    fun start(): List<ProtocolEvent> {
        check(state == State.NEW)
        state = State.TDI
        return listOf(
            ProtocolEvent.Progress("Reading Tile information"),
            ProtocolEvent.Write(preAuth(TDI_REQUEST, byteArrayOf(TDI_FEATURES.toByte()))),
        )
    }

    fun receive(packet: ByteArray): List<ProtocolEvent> = runCatching {
        if (packet.isEmpty()) return emptyList()
        when {
            packet[0].toInt() == 0 -> receiveConnectionless(packet)
            packet[0].toInt() and 0xff == channelPrefix -> receiveChannel(packet)
            else -> emptyList()
        }
    }.getOrElse { fail(it.message ?: "Protocol error") }

    fun playHigh(): List<ProtocolEvent> {
        check(state == State.READY) { "Tile is not authenticated" }
        val volume = byteArrayOf(PLAY.toByte(), VOLUME_FORMAT.toByte(), VOLUME_HIGH.toByte())
        val payload = if (supports(TSD)) volume + byteArrayOf(30) else volume
        return listOf(ProtocolEvent.Progress("Starting sound"), ProtocolEvent.Write(channel(SONG_REQUEST, payload)))
    }

    fun stop(): List<ProtocolEvent> {
        if (state != State.READY) return emptyList()
        return listOf(ProtocolEvent.Progress("Stopping sound"), ProtocolEvent.Write(channel(SONG_REQUEST, byteArrayOf(STOP.toByte()))))
    }

    fun state(): State = state

    private fun receiveConnectionless(packet: ByteArray): List<ProtocolEvent> {
        if (packet.size < 6) return emptyList()
        val addressed = packet.copyOfRange(1, 5)
        if (!addressed.contentEquals(mepId) && !addressed.all { it == 0xff.toByte() }) return emptyList()
        val prefix = packet[5].toInt() and 0xff
        val body = packet.copyOfRange(6, packet.size)
        return when (prefix) {
            TDI_RESPONSE -> receiveTdi(body)
            AUTH_RESPONSE, ASSOCIATE_RESPONSE -> {
                if (body.size < 14) return fail("Short authentication response")
                state = State.AUTH
                listOf(
                    ProtocolEvent.Progress("Opening secure channel"),
                    ProtocolEvent.Write(preAuth(AUTH_CONFIRM, randA)),
                )
            }
            OPEN_CHANNEL_RESPONSE -> openChannel(body)
            else -> emptyList()
        }
    }

    private fun receiveTdi(body: ByteArray): List<ProtocolEvent> {
        if (state != State.TDI || body.isEmpty()) return emptyList()
        val response = body[0].toInt() and 0xff
        val data = body.copyOfRange(1, body.size)
        if (response != tdiRequest) return emptyList()
        when (response) {
            TDI_FEATURES -> tdiFeatures = data.firstOrNull()?.toInt()?.and(0xff) ?: 0
            TDI_TILE_ID -> tileId = data.hex()
            TDI_FIRMWARE -> firmware = data.toString(Charsets.UTF_8)
            TDI_MODEL -> model = data.toString(Charsets.UTF_8)
            TDI_HARDWARE -> hardware = data.toString(Charsets.UTF_8)
        }
        val next = (tdiRequest + 1..TDI_HARDWARE).firstOrNull { request ->
            tdiFeatures and (1 shl (request - TDI_TILE_ID)) != 0
        }
        return if (next != null) {
            tdiRequest = next
            listOf(ProtocolEvent.Write(preAuth(TDI_REQUEST, byteArrayOf(next.toByte()))))
        } else {
            state = State.AUTH
            listOf(
                ProtocolEvent.TileInfo(tileId, firmware, model, hardware),
                ProtocolEvent.Progress("Authenticating"),
                ProtocolEvent.Write(preAuth(AUTH_REQUEST, randA)),
            )
        }
    }

    private fun openChannel(body: ByteArray): List<ProtocolEvent> {
        if (body.size < 2) return fail("Short channel-open response")
        channelPrefix = body[0].toInt() and 0xff
        val channelData = body.copyOfRange(1, body.size)
        channelKey = TileCrypto.hmac(authKey, randA, channelData, channelPrefix, mepId).copyOf(16)
        state = State.CHANNEL
        return listOf(
            ProtocolEvent.Progress("Confirming secure channel"),
            ProtocolEvent.Write(channel(OPEN_CHANNEL_RESPONSE, byteArrayOf(CLOSE_CHANNEL_RESPONSE.toByte()))),
        )
    }

    private fun receiveChannel(packet: ByteArray): List<ProtocolEvent> {
        if (packet.size < 6) return fail("Short channel frame")
        val payload = packet.copyOfRange(1, packet.size - MIC_SIZE)
        val receivedMic = packet.copyOfRange(packet.size - MIC_SIZE, packet.size)
        val nextNonce = nonceT + 1
        val expectedMic = TileCrypto.hmac(
            channelKey,
            TileCrypto.longLittleEndian(nextNonce),
            TILE_TO_APP,
            payload.size,
            payload,
        ).copyOf(MIC_SIZE)
        if (!receivedMic.contentEquals(expectedMic)) return fail("Tile response MIC verification failed")
        nonceT = nextNonce
        if (payload.isEmpty()) return emptyList()
        val prefix = payload[0].toInt() and 0xff
        val body = payload.copyOfRange(1, payload.size)
        return when (prefix) {
            READY_RESPONSE -> receiveReady(body)
            TDG_RESPONSE -> receiveTdg(body)
            ADV_INT_RESPONSE -> receiveAdvInt(body)
            TCU_RESPONSE -> receiveTcu(body)
            TFC_RESPONSE -> continueSetup()
            TKA_RESPONSE -> receiveTka(body)
            TDT_RESPONSE -> receiveTdt(body)
            SONG_RESPONSE -> receiveSong(body)
            CLOSE_CHANNEL_RESPONSE -> fail("Tile closed the secure channel")
            else -> emptyList()
        }
    }

    private fun receiveReady(body: ByteArray): List<ProtocolEvent> {
        if (body.size < 2) return fail("Short READY response")
        val nonceData = body.copyOfRange(1, body.size)
        features = if (nonceData.size >= 7) {
            nonceData.copyOfRange(0, 3) + nonceData.copyOfRange(7, nonceData.size)
        } else {
            nonceData
        }
        state = State.SETUP
        return when {
            supports(TFC) -> listOf(ProtocolEvent.Progress("Checking firmware"), ProtocolEvent.Write(channel(TFC_REQUEST, byteArrayOf(1, 1))))
            else -> continueSetup()
        }
    }

    private fun continueSetup(): List<ProtocolEvent> = when {
        supports(TDG) -> listOf(ProtocolEvent.Progress("Reading diagnostics"), ProtocolEvent.Write(channel(TDG_REQUEST, byteArrayOf(1))))
        supports(TCU) -> sendTcu()
        else -> finishSetup()
    }

    private fun receiveTdg(body: ByteArray): List<ProtocolEvent> {
        if (body.isEmpty()) return emptyList()
        return when (body[0].toInt() and 0xff) {
            1 -> {
                val events = mutableListOf<ProtocolEvent>()
                if (supports(PPM)) events += ProtocolEvent.Write(channel(PPM_REQUEST, byteArrayOf(2)))
                if (supports(ADV_INT)) {
                    events += ProtocolEvent.Progress("Reading advertising settings")
                    events += ProtocolEvent.Write(channel(ADV_INT_REQUEST, byteArrayOf(2)))
                } else if (supports(TCU)) {
                    events += sendTcu()
                } else {
                    events += finishSetup()
                }
                events
            }
            2 -> emptyList()
            32 -> fail("Tile diagnostic request failed")
            else -> emptyList()
        }
    }

    private fun receiveAdvInt(body: ByteArray): List<ProtocolEvent> = when {
        body.firstOrNull()?.toInt()?.and(0xff) in setOf(2, 3) && supports(TCU) -> sendTcu()
        body.firstOrNull()?.toInt()?.and(0xff) in setOf(16, 17) -> finishSetup()
        else -> emptyList()
    }

    private fun sendTcu(): List<ProtocolEvent> {
        val params = littleEndianShort(288) + littleEndianShort(304) + littleEndianShort(4) + littleEndianShort(600)
        return listOf(ProtocolEvent.Progress("Optimizing connection"), ProtocolEvent.Write(channel(TCU_REQUEST, byteArrayOf(3) + params)))
    }

    private fun receiveTcu(body: ByteArray): List<ProtocolEvent> = when (body.firstOrNull()?.toInt()?.and(0xff)) {
        3, 32 -> finishSetup()
        else -> emptyList()
    }

    private fun finishSetup(): List<ProtocolEvent> {
        if (setupComplete) return emptyList()
        val events = mutableListOf<ProtocolEvent>()
        if (supports(TDT) && expectedTdtConfig.isNotBlank()) {
            val config = runCatching { Base64.getDecoder().decode(expectedTdtConfig) }.getOrDefault(ByteArray(0))
            if (config.size >= 4) events += ProtocolEvent.Write(channel(TDT_REQUEST, byteArrayOf(1) + config))
        }
        if (supports(TIME)) {
            val tileEpoch = (unixTimeSeconds() - TILE_EPOCH_SECONDS).coerceAtLeast(0)
            events += ProtocolEvent.Write(channel(TIME_REQUEST, byteArrayOf(3) + TileCrypto.longLittleEndian(tileEpoch)))
        }
        if (supports(TPFS)) {
            waitingForSongMap = true
            events += ProtocolEvent.Progress("Loading sound profile")
            events += ProtocolEvent.Write(channel(SONG_REQUEST, byteArrayOf(6)))
        } else {
            setupComplete = true
            state = State.READY
            events += ProtocolEvent.Authenticated
        }
        return events
    }

    private fun receiveTka(body: ByteArray): List<ProtocolEvent> = if (body.firstOrNull()?.toInt()?.and(0xff) == 3) {
        listOf(ProtocolEvent.Write(channel(TKA_REQUEST, byteArrayOf(3))))
    } else {
        emptyList()
    }

    private fun receiveTdt(body: ByteArray): List<ProtocolEvent> {
        if (body.size < 2 || body[0].toInt() and 0xff != TDT_NOTIFY) return emptyList()
        return when (body[1].toInt() and 0xff) {
            0x00 -> listOf(ProtocolEvent.ButtonPress(double = false))
            0x02 -> listOf(ProtocolEvent.ButtonPress(double = true))
            else -> emptyList()
        }
    }

    private fun receiveSong(body: ByteArray): List<ProtocolEvent> {
        val response = body.firstOrNull()?.toInt()?.and(0xff) ?: return emptyList()
        if (waitingForSongMap && response == 7) {
            waitingForSongMap = false
            setupComplete = true
            state = State.READY
            return listOf(ProtocolEvent.Authenticated)
        }
        return when (response) {
            2 -> listOf(ProtocolEvent.RingStarted)
            3 -> listOf(ProtocolEvent.RingStopped)
            32 -> fail("Tile rejected the sound command")
            else -> emptyList()
        }
    }

    private fun channel(toaPrefix: Int, toaData: ByteArray): ByteArray {
        check(channelKey.isNotEmpty())
        val payload = byteArrayOf(toaPrefix.toByte()) + toaData
        nonceA += 1
        val mic = TileCrypto.hmac(
            channelKey,
            TileCrypto.longLittleEndian(nonceA),
            APP_TO_TILE,
            payload.size,
            payload,
        ).copyOf(MIC_SIZE)
        return byteArrayOf(channelPrefix.toByte()) + payload + mic
    }

    private fun preAuth(prefix: Int, data: ByteArray) = byteArrayOf(0) + mepId + byteArrayOf(prefix.toByte()) + data

    private fun supports(feature: Feature): Boolean {
        var index = feature.byte
        if (index > 2) index += 1
        return index < features.size && features[index].toInt() and (1 shl feature.bit) != 0
    }

    private fun fail(message: String): List<ProtocolEvent> {
        state = State.FAILED
        return listOf(ProtocolEvent.Failure(message))
    }

    private fun littleEndianShort(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private data class Feature(val byte: Int, val bit: Int)

    companion object {
        private const val MIC_SIZE = 4
        private const val TILE_TO_APP = 0
        private const val APP_TO_TILE = 1
        private const val TILE_EPOCH_SECONDS = 1_451_606_400L

        private const val READY_RESPONSE = 1
        private const val TDT_REQUEST = 4
        private const val SONG_REQUEST = 5
        private const val PPM_REQUEST = 6
        private const val ADV_INT_REQUEST = 7
        private const val TKA_REQUEST = 8
        private const val TDG_REQUEST = 10
        private const val TCU_REQUEST = 12
        private const val TIME_REQUEST = 13
        private const val TFC_REQUEST = 15
        private const val AUTH_CONFIRM = 16
        private const val OPEN_CHANNEL_RESPONSE = 18
        private const val CLOSE_CHANNEL_RESPONSE = 19
        private const val TDI_REQUEST = 19
        private const val AUTH_REQUEST = 20

        private const val TDI_RESPONSE = 20
        private const val AUTH_RESPONSE = 21
        private const val ASSOCIATE_RESPONSE = 27
        private const val SONG_RESPONSE = 7
        private const val TDT_RESPONSE = 6
        private const val TDT_NOTIFY = 2
        private const val ADV_INT_RESPONSE = 9
        private const val TKA_RESPONSE = 10
        private const val TDG_RESPONSE = 12
        private const val TCU_RESPONSE = 14
        private const val TFC_RESPONSE = 17

        private const val TDI_FEATURES = 1
        private const val TDI_TILE_ID = 2
        private const val TDI_FIRMWARE = 3
        private const val TDI_MODEL = 4
        private const val TDI_HARDWARE = 5

        private const val STOP = 3
        private const val PLAY = 2
        private const val VOLUME_FORMAT = 1
        private const val VOLUME_HIGH = 3

        private val TDT = Feature(0, 2)
        private val PPM = Feature(0, 4)
        private val ADV_INT = Feature(0, 5)
        private val TDG = Feature(1, 2)
        private val TCU = Feature(1, 4)
        private val TIME = Feature(1, 5)
        private val TFC = Feature(2, 0)
        private val TPFS = Feature(2, 1)
        private val TSD = Feature(3, 1)
    }
}
