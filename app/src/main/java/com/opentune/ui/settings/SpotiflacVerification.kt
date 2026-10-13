package com.opentune.ui.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentune.data.lossless.LosslessRegistry
import com.opentune.data.lossless.SpotiflacSession
import com.opentune.data.lossless.SpotiflacVerificationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun SpotiflacVerification(onDismiss: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp), shape = MaterialTheme.shapes.large) {
            key(attempt) { VerificationAttempt(onDismiss, onRetry = { attempt++ }) }
        }
    }
}

/** User-operated, provider-hosted page. No challenge solving or native bridge in remote frames. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VerificationAttempt(onDismiss: () -> Unit, onRetry: () -> Unit) {
    val installId = remember { SpotiflacSession.installId() }
    val state = remember { java.util.UUID.randomUUID().toString().replace("-", "") }
    var issuer by remember { mutableStateOf<String?>(null) }
    var grant by remember { mutableStateOf<String?>(null) }
    var grantConsumed by remember { mutableStateOf(false) }
    var challenge by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("Connecting to SpotiFLAC…") }
    var busy by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose { webView?.stopLoading(); webView?.destroy() }
    }
    LaunchedEffect(Unit) {
        try {
            val base = LosslessRegistry.verificationBase(LosslessRegistry.snapshot())
            issuer = base
            val data = SpotiflacVerificationApi.bootstrap(base, installId)
            if (SpotiflacSession.save(data.toString(), base)) { onDismiss(); return@LaunchedEffect }
            challenge = SpotiflacVerificationApi.challengeUrl(data, base, state)
            status = if (challenge != null) "Complete the check on the provider's page below."
                else "The provider did not return a supported verification page. Other audio sources remain available."
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = "Could not connect to the verification provider. Other audio sources remain available."
        } finally { busy = false }
    }
    LaunchedEffect(challenge) {
        if (challenge != null) {
            delay(5 * 60_000L)
            if (!grantConsumed) {
                grantConsumed = true
                status = "Verification timed out. Start a new check or close this to continue listening."
            }
        }
    }
    LaunchedEffect(grant) {
        val token = grant ?: return@LaunchedEffect
        val base = issuer ?: return@LaunchedEffect
        busy = true
        status = "Check completed. Connecting your provider session…"
        try {
            val session = SpotiflacVerificationApi.exchange(base, installId, token)
            if (SpotiflacSession.save(session, base)) { onDismiss(); return@LaunchedEffect }
            status = "The provider returned no valid session. Start a new check to try again."
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = "The check completed, but the provider could not issue a session. Start a new check to try again."
        } finally { busy = false }
    }
    Column(Modifier.padding(16.dp)) {
        Text("Verify SpotiFLAC", style = MaterialTheme.typography.titleLarge)
        Text("Optional community provider. A successful check must also return a valid provider session. JioSaavn and YouTube do not need this verification.", Modifier.padding(vertical = 8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        challenge?.let { page ->
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame) return false
                                if (SpotiflacVerificationApi.isCallback(request.url)) {
                                    if (!grantConsumed) {
                                        val accepted = SpotiflacVerificationApi.grantFromCallback(request.url, state)
                                        if (accepted != null) {
                                            grantConsumed = true
                                            grant = accepted
                                        } else status = "Verification callback was rejected. Start a new check."
                                    }
                                    return true
                                }
                                return !SpotiflacVerificationApi.isChallengePage(request.url.toString(), page)
                            }
                        }
                        loadUrl(page)
                        webView = this
                    }
                },
            )
        }
        Row {
            TextButton(onClick = onDismiss) { Text("Close") }
            TextButton(onClick = onRetry, enabled = !busy) { Text("New check") }
        }
    }
}

