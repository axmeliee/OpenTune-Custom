package com.opentune.data.download

import java.io.File
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadAudioTest {
    @Test fun rejectsHtmlAndZeroFilledDownloads() {
        for (data in listOf("<html>Access denied</html>".toByteArray(), ByteArray(4096))) {
            val file = File.createTempFile("download", ".m4a")
            try {
                file.writeBytes(data)
                assertTrue(runCatching { DownloadAudio.validate(file) }.exceptionOrNull() is IOException)
            } finally { file.delete() }
        }
    }
}
