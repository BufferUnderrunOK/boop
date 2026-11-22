// Copied from colonelpanic8/tile-wear @fa914c9 (MIT License, Copyright (c) 2026 Ivan Malison).
// Only the package line is changed. https://github.com/colonelpanic8/tile-wear
package net.tileringer.boop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TileCrypto {
    fun hmac(secret: ByteArray, vararg parts: Any): ByteArray {
        val data = ByteArray(32)
        var offset = 0
        parts.forEach { part ->
            val bytes = when (part) {
                is ByteArray -> part
                is Int -> byteArrayOf(part.toByte())
                else -> error("Unsupported HMAC part")
            }
            val count = minOf(bytes.size, data.size - offset)
            if (count > 0) bytes.copyInto(data, offset, 0, count)
            offset += count
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(data)
        }
    }

    fun longLittleEndian(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(value)
        .array()

    fun uintLittleEndian(bytes: ByteArray): Long = ByteBuffer.wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
        .toLong() and 0xffffffffL
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

