package app.deckr.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.deckr.DeckrViewModel
import android.os.SystemClock
import app.deckr.ble.FoundPc
import app.deckr.ble.LinkState
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(vm: DeckrViewModel, permissions: Array<String>) {
    val context = LocalContext.current
    val state by vm.link.state.collectAsStateWithLifecycle()
    val found by vm.link.found.collectAsStateWithLifecycle()
    val pcName by vm.link.connectedName.collectAsStateWithLifecycle()
    val nearby by vm.link.nearby.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }

    // Android 11 and older only return scan results while Location is switched on.
    fun locationOk(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = context.getSystemService(LocationManager::class.java) ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }
    var locationOn by remember { mutableStateOf(locationOk()) }
    LaunchedEffect(Unit) { while (true) { locationOn = locationOk(); delay(2_000) } }

    fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions()
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(permissions) }

    // Keep looking for PCs while this screen is up; the view model connects to the last PC as soon
    // as it shows up. If a few seconds of scanning don't find it, try connecting to it directly:
    // a PC that already has a Bluetooth link to this phone (Phone Link and similar) can be
    // reachable even though the phone doesn't hear its advert.
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        var scanStarted = 0L
        var lastDirect = 0L
        while (true) {
            val s = vm.link.state.value
            val now = SystemClock.elapsedRealtime()
            if (vm.link.bluetoothEnabled) {
                if (s == LinkState.Idle || (s == LinkState.Failed && vm.autoConnect)) {
                    vm.link.startScan()
                    scanStarted = now
                } else if (s == LinkState.Scanning && vm.autoConnect && vm.link.found.value.isEmpty() &&
                    now - scanStarted > 4_000 && now - lastDirect > 15_000
                ) {
                    val target = vm.lastAddress?.let { FoundPc(it, vm.lastName ?: "Your PC", 0) }
                        ?: vm.link.pairedComputers().singleOrNull()
                    if (target != null) {
                        lastDirect = now
                        vm.connect(target.address, target.name)
                    }
                }
            }
            delay(1_000)
        }
    }

    // First time: if exactly one PC is around, just use it.
    LaunchedEffect(found.size, state) {
        if (vm.lastAddress == null && vm.autoConnect && state == LinkState.Scanning && found.size == 1) {
            delay(1_500)
            val only = vm.link.found.value.singleOrNull()
            if (only != null && vm.link.state.value == LinkState.Scanning) vm.connect(only.address, only.name)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DeckrColors.Bg)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Deckr", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = DeckrColors.Text)
            Text(
                "Open Deckr on your PC. Your phone finds it and connects by itself.",
                color = DeckrColors.TextDim,
            )

            if (!granted) {
                Button(onClick = { launcher.launch(permissions) }) { Text("Allow Bluetooth") }
            }

            StatusCard(state, granted, vm.link.bluetoothEnabled, locationOn, pcName ?: vm.lastName ?: "your PC")

            if (!locationOn) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                    Text("Turn on Location")
                }
            }

            val last = vm.lastAddress
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(found, key = { it.address }) { pc ->
                    val isLast = pc.address == last || pc.name == vm.lastName
                    PcRow(Icons.Rounded.Computer, pc.name, if (isLast) "Your PC" else "Tap to connect", enabled = true) {
                        vm.connect(pc.address, pc.name)
                    }
                }
                if (last != null && found.none { it.address == last || it.name == vm.lastName }) {
                    item {
                        PcRow(Icons.Rounded.History, vm.lastName ?: "Last PC", "Your PC. Tap to connect", enabled = granted) {
                            vm.connect(last, vm.lastName ?: "Last PC")
                        }
                    }
                }
                val paired = if (granted) vm.link.pairedComputers().filter { p -> p.address != last && found.none { it.address == p.address } } else emptyList()
                items(paired, key = { "p-" + it.address }) { pc ->
                    PcRow(Icons.Rounded.Computer, pc.name, "Paired with this phone. Tap to connect", enabled = true) {
                        vm.connect(pc.address, pc.name)
                    }
                }
                // Fallback: if the PC doesn't show up as a Deckr PC, let the user see everything the
                // phone hears and pick it by name or signal strength.
                if (granted && found.isEmpty() && nearby.isNotEmpty()) {
                    item {
                        Text(
                            if (showAll) "Hide nearby devices" else "Can't see your PC? Show all ${nearby.size} nearby devices",
                            color = DeckrColors.Accent,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { showAll = !showAll }
                                .padding(vertical = 6.dp),
                        )
                    }
                    if (showAll) {
                        items(nearby, key = { "n-" + it.address }) { d ->
                            PcRow(Icons.Rounded.Bluetooth, d.name, "Signal ${d.rssi} dBm. Strongest is usually closest", enabled = true) {
                                vm.connect(d.address, d.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: LinkState, granted: Boolean, btOn: Boolean, locationOn: Boolean, pcName: String) {
    val busy = state == LinkState.Scanning || state == LinkState.Connecting || state == LinkState.Approval
    val (text, color) = when {
        !granted -> "Deckr needs the Nearby devices permission to find your PC." to DeckrColors.TextDim
        !btOn -> "Bluetooth is off. Turn it on to continue." to DeckrColors.Danger
        !locationOn -> "This phone only finds Bluetooth devices while Location is on." to DeckrColors.Danger
        state == LinkState.NotDeckr -> "That device isn't a PC running Deckr. Pick another one." to DeckrColors.Danger
        state == LinkState.Approval -> "Click Allow on $pcName to let this phone in. You only need to do this once." to DeckrColors.Accent
        state == LinkState.Connecting -> "Connecting to $pcName…" to DeckrColors.TextDim
        state == LinkState.Scanning -> "Looking for your PC…" to DeckrColors.TextDim
        state == LinkState.Denied -> "$pcName didn't allow this phone. Tap it below to ask again." to DeckrColors.Danger
        state == LinkState.Failed -> "Couldn't connect. Is Deckr open on your PC?" to DeckrColors.Danger
        else -> "" to DeckrColors.TextDim
    }
    if (text.isEmpty()) return
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(DeckrColors.Surface, shape)
            .border(1.dp, if (state == LinkState.Approval) DeckrColors.Accent else DeckrColors.Outline, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = DeckrColors.Accent)
            Spacer(Modifier.size(14.dp))
        }
        Text(text, color = color, fontSize = 14.sp)
    }
}

@Composable
private fun PcRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(DeckrColors.Surface, shape)
            .border(1.dp, DeckrColors.Outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) DeckrColors.Accent else DeckrColors.TextDim)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DeckrColors.Text, fontWeight = FontWeight.Medium)
            Text(subtitle, color = DeckrColors.TextDim, fontSize = 13.sp)
        }
    }
}
