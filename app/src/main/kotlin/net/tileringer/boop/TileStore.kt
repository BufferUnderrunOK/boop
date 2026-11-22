package net.tileringer.boop

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One Tile from the account, with the auth key imported from tile-keys.json (made by mac/get_keys.py). */
data class TileInfo(
    val id: String,
    val name: String,
    val authKey: String,
    val expectedTdtConfig: String,
) {
    /**
     * Older Tiles advertise from the first 6 bytes of their Tile ID; newer ones set the top two bits
     * (a static random address). Verified on a ROYAL_ST1: id 1cea80dd612a… -> DC:EA:80:DD:61:2A.
     */
    fun matchesAddress(address: String): Boolean {
        val mac = address.replace(":", "").lowercase()
        if (mac.length != 12 || id.length < 12) return false
        val prefix = id.substring(0, 12).lowercase()
        val withTopBits = "%02x".format(prefix.substring(0, 2).toInt(16) or 0xC0) + prefix.substring(2)
        return mac == prefix || mac == withTopBits
    }
}

/**
 * The imported Tile keys, kept in app-private storage and encrypted with an AES key that lives in
 * the Android Keystore (it never leaves the phone's secure hardware, and isn't in backups).
 */
object TileStore {
    private const val FILE = "tiles.enc"
    private const val KEY_ALIAS = "boop-tile-keys"

    /** The imported Tiles, or an empty list if nothing has been imported yet. */
    fun load(context: Context): List<TileInfo> {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return emptyList()
        return runCatching { parse(decrypt(file.readBytes())) }.getOrDefault(emptyList())
    }

    /** Validates tile-keys.json text and stores it. Returns how many usable Tiles it holds. */
    fun import(context: Context, json: String): Int {
        val tiles = runCatching { parse(json) }.getOrElse { error("That isn't a tile-keys.json file") }
        check(tiles.isNotEmpty()) { "No Tiles with keys in that file" }
        File(context.filesDir, FILE).writeBytes(encrypt(json))
        return tiles.size
    }

    private fun parse(json: String): List<TileInfo> {
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }
            .filter { !it.isNull("auth_key") && it.optString("tile_id").length == 16 }
            .filter { runCatching { Base64.getDecoder().decode(it.getString("auth_key")).size == 16 }.getOrDefault(false) }
            .map {
                TileInfo(
                    id = it.getString("tile_id"),
                    name = it.optString("name", it.getString("tile_id")),
                    authKey = it.getString("auth_key"),
                    expectedTdtConfig = if (it.isNull("expected_tdt_cmd_config")) "" else it.getString("expected_tdt_cmd_config"),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    /** [iv length][iv][AES-GCM ciphertext] */
    private fun encrypt(text: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(blob: ByteArray): String {
        val ivSize = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(blob, 1 + ivSize, blob.size - 1 - ivSize).toString(Charsets.UTF_8)
    }
}
