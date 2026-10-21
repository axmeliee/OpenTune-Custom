package com.opentune.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentune.data.addons.*
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AudioAddonsSheet(onDismiss: () -> Unit) {
    val addons by AddonStore.addons.collectAsState()
    val playback by AppSettings.playback.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) { CommunityAddons.refresh() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text("Audio sources", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { notice = if (CommunityAddons.refresh(force = true)) "Community sources refreshed" else "Couldn't refresh. Your saved sources are still available." } }) { Icon(Icons.Rounded.Refresh, "Refresh community sources") }
                }
                Text("YOUR SOUND, YOUR ORDER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("OpenTune starts music quickly, then looks for better audio. Max quality checks your enabled sources for the best available lossless copy. Equal quality follows this order.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column {
                        addons.forEachIndexed { index, addon ->
                            val blocked = addon.manifest.outputRequired && !AddonStore.outputAllowed()
                            Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == addon.key) null else addon.key }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.Rounded.Extension, null, tint = if (addon.enabled && !blocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(addon.manifest.name, style = MaterialTheme.typography.titleMedium)
                                    Text(if (addon.admin) "Added by admin" else "Your source", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(checks[addon.key] ?: if (blocked) "Needs a supported wired, USB or HDMI output" else if (!addon.enabled) "Disabled" else "Lossless · ${if (addon.manifest.downloads) "Streaming & downloads" else "Streaming only"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(addon.enabled, { AddonStore.enable(addon.key, it) })
                                Icon(Icons.Rounded.ExpandMore, "Source options")
                            }
                            if (expanded == addon.key) Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                if (!addon.admin) Text(AddonProtocol.url(addon.base).host, style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(enabled = index > 0, onClick = { AddonStore.move(addon.key, -1) }) { Text("Move up") }
                                    TextButton(enabled = index < addons.lastIndex, onClick = { AddonStore.move(addon.key, 1) }) { Text("Move down") }
                                    TextButton(onClick = { scope.launch {
                                        if (!AddonStore.usable(addon)) { checks = checks + (addon.key to "Disabled or waiting for a supported output"); return@launch }
                                        checks = checks + (addon.key to "Checking…")
                                        try { AddonStore.refreshed(addon.key, AddonClient.forBase(addon.base).manifest()); checks = checks + (addon.key to "Available · recording support varies") }
                                        catch (e: CancellationException) { throw e }
                                        catch (e: AddonHttp.Failure) { checks = checks + (addon.key to "HTTP ${e.status}") }
                                        catch (_: Exception) { checks = checks + (addon.key to "Service unavailable") }
                                    } }) { Text("Check") }
                                    if (!addon.admin) TextButton(onClick = { AddonStore.remove(addon.key) }) { Text("Remove") }
                                }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                        }
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.GraphicEq, null)
                            Column(Modifier.weight(1f)) { Text("JioSaavn", style = MaterialTheme.typography.titleMedium); Text("Fast 320 kbps fallback · lossy", style = MaterialTheme.typography.bodySmall) }
                            Switch(playback.jioSaavnQuality, { AppSettings.updatePlayback { p -> p.copy(jioSaavnQuality = it) } })
                        }
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.PlayCircle, null)
                            Column(Modifier.weight(1f)) { Text("YouTube Music", style = MaterialTheme.typography.titleMedium); Text("Catalog, radio & fallback", style = MaterialTheme.typography.bodySmall) }
                            Text("Always on", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add your own source") }
                    }
                }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text("If a source has no match or cannot be reached, the next one is checked. Signal path shows the actual decoded format. Bluetooth may still compress the output.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { if (!working) adding = false }, title = { Text("Add audio source") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Paste a service root or manifest URL. OpenTune loads its capabilities without installing executable code.")
            OutlinedTextField(input, { input = it.take(2048) }, label = { Text("HTTPS addon URL") }, singleLine = true)
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Text(it) }
        }
    }, confirmButton = { TextButton(enabled = input.isNotBlank() && !working, onClick = { scope.launch {
        working = true; notice = null
        try { val base = AddonProtocol.normalize(input); AddonStore.add(base, AddonClient.forBase(base).manifest()); input = ""; adding = false; notice = "Source added" }
        catch (e: CancellationException) { throw e }
        catch (e: AddonHttp.Failure) { notice = "Service returned HTTP ${e.status}" }
        catch (_: Exception) { notice = "Could not load this manifest. Check the URL and connection." }
        finally { working = false }
    } }) { Text("Add source") } }, dismissButton = { TextButton(enabled = !working, onClick = { adding = false }) { Text("Cancel") } })
}
