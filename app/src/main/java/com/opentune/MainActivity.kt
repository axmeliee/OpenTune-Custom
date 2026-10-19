package com.opentune

import androidx.compose.runtime.getValue
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentune.data.account.AccountStore
import androidx.compose.animation.togetherWith
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.WelcomeState
import com.opentune.ui.AppRoot
import com.opentune.ui.welcome.WelcomeScreen
import com.opentune.ui.Links
import com.opentune.ui.PlayerViewModel
import com.opentune.ui.theme.OpenTuneTheme
import com.opentune.ui.theme.rememberArtworkSeed

class MainActivity : ComponentActivity() {
    private var tv = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        tv = com.opentune.ui.tv.Tv.isTv(this)
        setContent {
            val vm: PlayerViewModel = viewModel()
            val theme by AppSettings.theme.collectAsState()
            val ui by AppSettings.ui.collectAsState()
            LaunchedEffect(ui.highRefreshRate) { preferRefreshRate(ui.highRefreshRate) }
            // A TV dims and starts its screensaver after a few minutes without a key press;
            // while music plays from here, the cover and lyrics stay on screen instead.
            val playing by vm.isPlaying.collectAsState()
            LaunchedEffect(playing) {
                if (!tv) return@LaunchedEffect
                if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val song by vm.currentSong.collectAsState()
            OpenTuneTheme(theme, artworkSeed = rememberArtworkSeed(song?.thumbnailUrl)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Some TVs crop the picture's edges: keep pages and buttons inside the safe area.
                    androidx.compose.foundation.layout.Box(if (tv) Modifier.padding(horizontal = 28.dp, vertical = 16.dp) else Modifier) {
                    // A fresh install's welcome comes first; Home, built after it, starts from the picks.
                    androidx.compose.animation.AnimatedContent(
                        ui.welcome == WelcomeState.DUE,
                        transitionSpec = {
                            if (ui.reduceAnimation) {
                                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) togetherWith
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                            } else {
                                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(320, delayMillis = 80)) +
                                    androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(420), initialScale = 0.96f)) togetherWith
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))
                            }
                        },
                        label = "welcome",
                    ) { welcome ->
                        if (welcome) WelcomeScreen(onDone = {}) else AppRoot(vm)
                    }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The process may have started in the background, offline (after an update, say).
        AccountStore.ensureProfile()
        // Playlist changes that couldn't reach YouTube Music last time try again.
        if (com.opentune.data.library.PlaylistSync.pending.value.isNotEmpty()) com.opentune.data.library.PlaylistSync.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) = Links.receive(intent)

    /**
     * With [fastest], asks for the screen's quickest refresh rate at its
     * current resolution, so scrolling and animation run at 90 or 120 Hz on
     * phones that otherwise hold apps at 60. Without it the system decides.
     */
    private fun preferRefreshRate(fastest: Boolean) {
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay) ?: return
        val now = display.mode
        val best = if (!fastest) null else display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxByOrNull { it.refreshRate }
        val id = best?.modeId ?: 0
        if (window.attributes.preferredDisplayModeId != id) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = id }
        }
    }
}
