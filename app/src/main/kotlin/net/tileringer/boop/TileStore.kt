package net.tileringer.boop

import android.content.Context
import org.json.JSONArray

/** One Tile from the account, with the auth key baked in from tile-keys.json (see app/build.gradle.kts). */
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

object TileStore {
    fun load(context: Context): List<TileInfo> {
        val json = context.assets.open("tiles.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }
            .filter { !it.isNull("auth_key") && it.optString("tile_id").length == 16 }
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
}
