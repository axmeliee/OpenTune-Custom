package com.opentune.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.opentune.data.Http

fun main() {
    // Covers on the same HTTP client as everything else.
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .build()
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "OpenTune",
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(760, 520)
            DesktopApp()
        }
    }
}
