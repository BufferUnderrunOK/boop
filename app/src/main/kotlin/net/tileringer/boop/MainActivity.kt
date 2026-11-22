package net.tileringer.boop

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Plum = Color(0xFF5B2E5E)
private val Pink = Color(0xFFFF6F91)
private val Blush = Color(0xFFFFE0E8)
private val Lilac = Color(0xFFE7DDFB)
private val Cream = Color(0xFFFFF4F6)

private sealed interface CardState {
    data object Idle : CardState
    data class Busy(val message: String) : CardState
    data object Ringing : CardState
    data class Oops(val message: String) : CardState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tiles = TileStore.load(this)
        if (WatchService.isEnabled(this) && WatchService.hasPermissions(this)) WatchService.setEnabled(this, true)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Pink, background = Cream, surface = Color.White)) {
                Surface(Modifier.fillMaxSize(), color = Cream) { BoopScreen(tiles) }
            }
        }
    }
}

@Composable
private fun BoopScreen(tiles: List<TileInfo>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ringer = remember { TileRinger(context.applicationContext) }
    val states = remember { mutableStateMapOf<String, CardState>() }
    var active by remember { mutableStateOf<RingHandle?>(null) }
    var pending by remember { mutableStateOf<TileInfo?>(null) }

    fun hasPermissions() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun boop(tile: TileInfo) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter?.isEnabled != true) {
            states[tile.id] = CardState.Oops("Bluetooth is off. Turn it on and boop again.")
            runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        scope.launch {
            states[tile.id] = CardState.Busy("Looking for ${tile.name}…")
            try {
                val ringing = ringer.ring(tile, tiles) { msg -> states[tile.id] = CardState.Busy(msg) }
                active = ringing
                states[tile.id] = CardState.Ringing
                ringing.awaitEnd(maxMillis = 32_000)
                ringing.release()
                if (states[tile.id] == CardState.Ringing) states[tile.id] = CardState.Idle
            } catch (e: Throwable) {
                states[tile.id] = CardState.Oops(e.message ?: "Something went wrong")
            } finally {
                active = null
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val tile = pending
        pending = null
        if (tile != null) {
            if (granted.values.all { it }) boop(tile)
            else states[tile.id] = CardState.Oops("Boop needs the Nearby devices permission to find Tiles.")
        }
    }

    val anyBusy = states.values.any { it is CardState.Busy || it is CardState.Ringing }
    val linked by Watcher.linked.collectAsState()
    val alarmingTile by FindPhoneAlarm.alarmingTile.collectAsState()
    var watchOn by remember { mutableStateOf(WatchService.isEnabled(context)) }
    var watchNote by remember { mutableStateOf<String?>(null) }
    val watchPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (WatchService.hasPermissions(context)) {
            WatchService.setEnabled(context, true); watchOn = true; watchNote = null
        } else {
            watchNote = "Kitty watch needs Nearby devices and Notifications."
        }
    }
    fun setWatch(on: Boolean) {
        if (!on) { WatchService.setEnabled(context, false); watchOn = false; return }
        if (WatchService.hasPermissions(context)) { WatchService.setEnabled(context, true); watchOn = true; watchNote = null }
        else watchPermissionLauncher.launch(WatchService.requiredPermissions)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 10.dp, start = 4.dp)) {
                Text("Boop", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Plum)
                Text("tap a kitty to make its Tile sing", fontSize = 16.sp, color = Plum.copy(alpha = 0.7f))
            }
        }
        alarmingTile?.let { name ->
            item { FoundItBanner(name) { FindPhoneAlarm.stop(context) } }
        }
        item { WatchToggle(watchOn, linked.size, watchNote, ::setWatch) }
        if (tiles.isEmpty()) item { Text("No Tiles with keys were baked into this build.", color = Plum) }
        items(tiles, key = { it.id }) { tile ->
            val state = states[tile.id] ?: CardState.Idle
            TileCard(
                tile = tile,
                state = state,
                linked = tile.id in linked,
                enabled = !anyBusy || state is CardState.Ringing,
                onBoop = {
                    if (hasPermissions()) boop(tile)
                    else {
                        pending = tile
                        permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                    }
                },
                onHush = {
                    val ringing = active
                    states[tile.id] = CardState.Idle
                    if (ringing != null) scope.launch { ringing.stop() }
                },
            )
        }
    }
}

@Composable
private fun TileCard(tile: TileInfo, state: CardState, linked: Boolean, enabled: Boolean, onBoop: () -> Unit, onHush: () -> Unit) {
    val ringing = state is CardState.Ringing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .clickable(enabled = enabled && !ringing && state !is CardState.Busy, onClick = onBoop)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Kitty(startled = ringing || state is CardState.Busy, shaking = ringing)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(tile.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Plum)
            val subtitle = when (state) {
                CardState.Idle -> if (linked) "awake & listening" else "napping"
                is CardState.Busy -> state.message
                CardState.Ringing -> "AAAA! ringing!"
                is CardState.Oops -> state.message
            }
            Text(
                subtitle,
                fontSize = 14.sp,
                color = if (state is CardState.Oops) Color(0xFFC2185B) else Plum.copy(alpha = 0.65f),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (ringing) {
            Button(onClick = onHush, colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = Plum)) {
                Text("Shh", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onBoop,
                enabled = enabled && state !is CardState.Busy,
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Color.White),
            ) {
                Text("Boop!", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Kitty(startled: Boolean, shaking: Boolean) {
    val transition = rememberInfiniteTransition(label = "shake")
    val angle by transition.animateFloat(
        initialValue = -9f, targetValue = 9f,
        animationSpec = infiniteRepeatable(tween(70), RepeatMode.Reverse), label = "angle",
    )
    val bounce by transition.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "bounce",
    )
    Box(
        modifier = Modifier.size(72.dp).clip(CircleShape).background(if (startled) Blush else Lilac),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(if (startled) R.drawable.cat_startled else R.drawable.cat_calm),
            contentDescription = if (startled) "startled kitty" else "sleepy kitty",
            modifier = Modifier
                .size(60.dp)
                .then(if (shaking) Modifier.rotate(angle).scale(bounce) else Modifier),
        )
    }
}

@Composable
private fun WatchToggle(on: Boolean, linkedCount: Int, note: String?, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Lilac).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Kitty watch", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Plum)
            Text(
                note ?: when {
                    !on -> "turn on so a double-press on a Tile makes this phone yowl"
                    linkedCount == 0 -> "on, looking for Tiles nearby…"
                    else -> "on, listening to $linkedCount ${if (linkedCount == 1) "Tile" else "Tiles"}"
                },
                fontSize = 14.sp,
                color = if (note != null) Color(0xFFC2185B) else Plum.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Pink, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun FoundItBanner(tileName: String, onFound: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Blush).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Kitty(startled = true, shaking = true)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("I'm right here!!", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Plum)
            Text("$tileName is looking for this phone", fontSize = 14.sp, color = Plum.copy(alpha = 0.7f))
        }
        Button(onClick = onFound, colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Color.White)) {
            Text("Found it!", fontWeight = FontWeight.Bold)
        }
    }
}
