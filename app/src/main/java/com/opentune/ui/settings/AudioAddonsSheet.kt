package com.opentune.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.addons.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AudioAddonsSheet(onDismiss: () -> Unit) {
    val addons by AddonStore.addons.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<AudioAddon?>(null) }
    var checks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Audio add-ons", style = MaterialTheme.typography.headlineSmall)
            Text("Add a service URL to find lossless audio. Enabled add-ons are checked from top to bottom; the first matching source is used.")
            OutlinedTextField(input, { input = it.take(2048) }, Modifier.fillMaxWidth(), label = { Text("Add-on or manifest URL") }, singleLine = true)
            Button(enabled = input.isNotBlank() && !working, onClick = { scope.launch {
                working = true; notice = null
                try {
                    val base = AddonProtocol.normalize(input)
                    val existing = addons.firstOrNull { it.base == base }
                    require(existing == null || !existing.manifest.outputRequired || AddonStore.outputAllowed()) { "Connect a wired, USB or HDMI output before checking this add-on." }
                    val manifest = AddonClient.forBase(base).manifest()
                    AddonStore.add(base, manifest)
                    input = ""; notice = "${manifest.name} added. Use the arrows to set its priority."
                } catch (e: CancellationException) { throw e }
                catch (e: AddonHttp.Failure) { notice = "The service returned HTTP ${e.status}. Check the URL or try later." }
                catch (_: Exception) { notice = "Could not add this service. Check the HTTPS manifest URL and your connection." }
                finally { working = false }
            } }) { Text(if (working) "Checking manifest…" else "Add URL") }
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Source priority", style = MaterialTheme.typography.titleLarge)
            if (addons.isEmpty()) Text("No add-ons yet. Your usual audio sources keep working.")
            addons.forEachIndexed { index, addon ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${index + 1}. ${addon.manifest.name}", style = MaterialTheme.typography.titleMedium)
                                Text(AddonProtocol.url(addon.base).host, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(addon.enabled, { AddonStore.enable(addon.key, it) })
                        }
                        Text("Version ${addon.manifest.version.ifBlank { "not stated" }} · ${if (addon.manifest.downloads) "Streaming and downloads" else "Streaming only"}", style = MaterialTheme.typography.bodySmall)
                        if (addon.manifest.outputRequired) Text("Requires a wired, USB or HDMI output. Bluetooth capability is not verified.", style = MaterialTheme.typography.bodySmall)
                        if (addon.manifest.qualities.isNotEmpty()) Text("Qualities: ${addon.manifest.qualities.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        checks[addon.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = index > 0, onClick = { AddonStore.move(addon.key, -1) }) { Text("Move up") }
                            OutlinedButton(enabled = index < addons.lastIndex, onClick = { AddonStore.move(addon.key, 1) }) { Text("Move down") }
                            TextButton(onClick = { scope.launch {
                                if (!AddonStore.usable(addon)) { checks = checks + (addon.key to "Disabled or waiting for a supported output"); return@launch }
                                checks = checks + (addon.key to "Checking…")
                                try { val manifest = AddonClient.forBase(addon.base).manifest(); AddonStore.refreshed(addon.key, manifest); checks = checks + (addon.key to "Manifest available · playback availability depends on the recording") }
                                catch (e: CancellationException) { throw e }
                                catch (e: AddonHttp.Failure) { checks = checks + (addon.key to "HTTP ${e.status}") }
                                catch (_: Exception) { checks = checks + (addon.key to "Service unavailable") }
                            } }) { Text("Check") }
                            TextButton(onClick = { deleting = addon }) { Text("Remove") }
                        }
                    }
                }
            }
            Text("Music starts through the usual fast source while add-ons are checked in the background. Signal path shows the selected source and decoded quality. Lossless labels do not guarantee a lossless Bluetooth connection.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
    deleting?.let { addon -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove add-on?") },
        text = { Text("Remove ${addon.manifest.name}? Downloaded songs stay on your device.") },
        confirmButton = { TextButton(onClick = { AddonStore.remove(addon.key); deleting = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
}
