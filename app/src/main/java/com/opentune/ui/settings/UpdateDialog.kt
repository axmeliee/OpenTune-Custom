package com.opentune.ui.settings

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.opentune.BuildConfig
import com.opentune.data.UpdateCheck
import com.opentune.data.Updater
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A newer release: what changed, and a button that downloads the APK for
 * this phone and opens Android's installer. Falls back to the release page
 * when the release has no APK to offer.
 */
@Composable
fun UpdateDialog(found: UpdateCheck.Release, onDismiss: () -> Unit, onNotNow: (UpdateCheck.Release) -> Unit = { onDismiss() }) {
    val context = LocalContext.current
    // Swapped for a newer release if one came out since [found] was.
    var release by remember(found) { mutableStateOf(found) }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val apk = release.apk
    var kept by remember { mutableStateOf(Updater.cachedBytes(context)) }

    fun start() {
        if (!Updater.canInstall(context)) {
            runCatching { context.startActivity(Updater.allowInstallsIntent(context)) }
            error = "Allow OpenTune to install apps, then tap Update again."
            return
        }
        error = null
        progress = 0f
        job = scope.launch {
            try {
                release = UpdateCheck.newest(context, release)
                val file = Updater.download(context, release) { p -> progress = p }
                Updater.install(context, file)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Download failed"
            } finally {
                progress = null
                kept = Updater.cachedBytes(context)
            }
        }
    }

    FloatingDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("OpenTune ${release.version}") },
        text = {
            Column {
                Text(
                    "You have ${BuildConfig.VERSION_NAME}." + (apk?.takeIf { it.bytes > 0 }?.let { " The update is ${Formatter.formatShortFileSize(context, it.bytes)}." } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    )
                }
                progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                    Text("Downloading… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                if (progress == null && kept > 0) {
                    Text(
                        "Delete the downloaded update (${Formatter.formatShortFileSize(context, kept)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp).clickable { Updater.deleteCached(context); kept = 0 },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        confirmButton = {
            if (apk == null) {
                SheetButton("Open release page", onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, release.page.toUri())) }
                    onDismiss()
                })
            } else {
                SheetButton("Update", enabled = progress == null, onClick = ::start)
            }
        },
        dismissButton = {
            if (progress != null) {
                SheetButton("Cancel", onClick = { job?.cancel() })
            } else {
                SheetButton("Not now", onClick = { onNotNow(release) }, closes = true)
            }
        },
    )
}
