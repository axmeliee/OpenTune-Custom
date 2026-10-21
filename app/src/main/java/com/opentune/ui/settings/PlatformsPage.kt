package com.opentune.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun PlatformsPage(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pairing by remember { mutableStateOf(false) }
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }
    if (pairing) com.opentune.ui.account.ReceiveSessionSheet { pairing = false }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text("More ways to listen", style = MaterialTheme.typography.headlineSmall)
                }
                Text("OpenTune on Web & TV", style = MaterialTheme.typography.displaySmall)
                Text("Your music deserves every screen.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Language, null, Modifier.size(36.dp))
                        Text("In your browser", style = MaterialTheme.typography.headlineSmall)
                        Text("Browse, search and listen on a computer. The web player uses a companion browser extension for YouTube Music playback. Your library stays in that browser.")
                        Text("Preview · available when this update's website is published", style = MaterialTheme.typography.labelMedium)
                        FilledTonalButton(onClick = { open("https://justshivv.github.io/Opentune/player/") }) { Text("Open web player") }
                    }
                }
                Card(shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Tv, null, Modifier.size(36.dp))
                        Text("On Android TV", style = MaterialTheme.typography.headlineSmall)
                        Text("Install the Android APK on your TV. In Settings → Account, choose Sign in with your phone. Scan the QR using a phone with OpenTune, on the same Wi-Fi, then approve sharing your YouTube Music session.")
                        Text("No password typing with a remote. Pairing expires after two minutes. Your phone and TV keep their own local libraries.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = { pairing = true }) { Text("Show sign-in QR on this device") }
                        TextButton(onClick = { open("https://github.com/justshivv/Opentune/releases") }) { Text("Get the Android app") }
                    }
                }
                CommunityBanner()
            }
        }
    }
}
