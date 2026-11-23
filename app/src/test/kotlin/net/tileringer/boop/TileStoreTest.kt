package net.tileringer.boop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileStoreTest {
    private val key = "AAECAwQFBgcICQoLDA0ODw==" // 16 bytes
    private fun tile(id: String) = TileInfo(id = id, name = "t", authKey = key, expectedTdtConfig = "")

    @Test
    fun `address matching covers plain and static-random forms`() {
        // Verified on a ROYAL_ST1: Tile ID 1cea80dd612a... advertises as DC:EA:80:DD:61:2A.
        val badge = tile("1cea80dd612aa3cf")
        assertTrue(badge.matchesAddress("DC:EA:80:DD:61:2A"))
        assertTrue(badge.matchesAddress("1C:EA:80:DD:61:2A"))
        assertTrue(badge.matchesAddress("dc:ea:80:dd:61:2a"))
        assertFalse(badge.matchesAddress("DC:EA:80:DD:61:2B"))
        assertFalse(badge.matchesAddress("not an address"))
    }

    @Test
    fun `parse keeps only Tiles with a valid 16-byte key, sorted by name`() {
        val json = """
            [
              {"tile_id": "1cea80dd612aa3cf", "name": "Wallet", "auth_key": "$key", "expected_tdt_cmd_config": "DFUjMg=="},
              {"tile_id": "0ad553913657f41e", "name": "Keys", "auth_key": "$key", "expected_tdt_cmd_config": null},
              {"tile_id": "p!5b3507a819daa0260801f59cd996bdff", "name": "Phone", "auth_key": null},
              {"tile_id": "afcd4a37d5b488e0", "name": "Short key", "auth_key": "AAEC"},
              {"tile_id": "bd47a646c9cd0f78", "name": "Not base64", "auth_key": "%%%%"}
            ]
        """.trimIndent()
        val tiles = TileStore.parse(json)
        assertEquals(listOf("Keys", "Wallet"), tiles.map { it.name })
        assertEquals("", tiles.first().expectedTdtConfig)
        assertEquals("DFUjMg==", tiles.last().expectedTdtConfig)
    }

    @Test(expected = org.json.JSONException::class)
    fun `parse rejects a file that isn't a JSON array`() {
        TileStore.parse("{\"hello\": 1}")
    }
}
