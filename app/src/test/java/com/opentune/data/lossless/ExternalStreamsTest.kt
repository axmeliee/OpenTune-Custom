package com.opentune.data.lossless

import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExternalStreamsTest {
    @Test fun replacingAResultDoesNotInvalidateBytesThePlayerIsStillReading() {
        AppSettings.init(RuntimeEnvironment.getApplication())
        AppSettings.updatePlayback { it.copy(wifiQuality = AudioQuality.MAX, mobileQuality = AudioQuality.MAX, losslessUnmeteredOnly = false) }
        val before = ExternalStreams.Rendition("old", "https://cdn.example/old", "JioSaavn", false, System.nanoTime() / 1_000_000)
        val after = before.copy(key = "new", url = "https://cdn.example/new", lossless = true)
        ExternalStreams.remember("song", before)
        ExternalStreams.remember("song", after)
        assertEquals(before.url, ExternalStreams.resolve("song", "old"))
        assertEquals(after.url, ExternalStreams.resolve("song", "new"))
        assertNull(ExternalStreams.get("different-song", "new"))
        ExternalStreams.retryLookups()
        assertEquals(after.url, ExternalStreams.resolve("song", "new"))
        ExternalStreams.failed("song")
        assertNull(ExternalStreams.get("song", "old"))
        assertNull(ExternalStreams.get("song", "new"))
    }
}
