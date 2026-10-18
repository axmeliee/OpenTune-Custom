package com.opentune.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdaterCacheTest {
    @Test fun cleanupKeepsNewerCustomApksAndRemovesObsoleteOrIncompleteDownloads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val newer = File(dir, "OpenTune-v9999.0.0-custom.1-universal.apk")
        val obsolete = File(dir, "OpenTune-v0.0.0-custom.1-universal.apk")
        val partial = File(dir, "OpenTune-v9999.0.0-custom.1-universal.apk.part")
        try {
            listOf(newer, obsolete, partial).forEach { it.writeText("download") }
            Updater.clearInstalled(context)
            assertTrue(newer.exists())
            assertFalse(obsolete.exists())
            assertFalse(partial.exists())
        } finally {
            listOf(newer, obsolete, partial).forEach { it.delete() }
        }
    }
}
