package com.opentune.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun comparesNumberByNumber() {
        assertTrue(UpdateCheck.isNewer("1.10", "1.9"))
        assertTrue(UpdateCheck.isNewer("2.0.1", "2.0"))
        assertFalse(UpdateCheck.isNewer("1.0", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("0.9", "1.0"))
    }

    @Test fun comparesCustomRevisionsAfterTheUpstreamVersion() {
        assertTrue(UpdateCheck.isNewer("0.4.18-custom.1", "0.4.18"))
        assertTrue(UpdateCheck.isNewer("0.4.18-custom.10", "0.4.18-custom.2"))
        assertTrue(UpdateCheck.isNewer("0.4.19-custom.1", "0.4.18-custom.10"))
        assertFalse(UpdateCheck.isNewer("0.4.18-custom.1", "0.4.18-custom.1"))
        assertFalse(UpdateCheck.isNewer("0.4.18-custom.1", "0.4.19-custom.1"))
    }

    private val release = Json.parseToJsonElement(
        """
        {"tag_name":"v0.3.0","html_url":"https://github.com/justshivv/Opentune/releases/tag/v0.3.0","body":"Notes",
         "assets":[
          {"name":"OpenTune-v0.3.0-armeabi-v7a.apk","browser_download_url":"https://x/v7a.apk","size":9000000},
          {"name":"OpenTune-v0.3.0-arm64-v8a.apk","browser_download_url":"https://x/arm64.apk","size":9300000},
          {"name":"OpenTune-v0.3.0-universal.apk","browser_download_url":"https://x/universal.apk","size":11800000},
          {"name":"SHA256SUMS.txt","browser_download_url":"https://x/sums","size":300}
         ]}
        """,
    ).jsonObject

    @Test fun picksTheApkForThePhonesProcessor() {
        val r = UpdateCheck.parse(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!
        assertEquals("0.3.0", r.version)
        assertEquals("OpenTune-v0.3.0-arm64-v8a.apk", r.apk?.name)
        assertEquals(9_300_000L, r.apk?.bytes)
        assertEquals("SHA256SUMS.txt", r.sums?.name)
        assertEquals("Notes", r.notes)
    }

    @Test fun fallsBackToUniversal() {
        assertEquals("OpenTune-v0.3.0-universal.apk", UpdateCheck.parse(release, listOf("riscv64"))?.apk?.name)
    }

    @Test fun readsTheVersionFromAnApkName() {
        assertEquals("0.5.0", Updater.versionOf("OpenTune-v0.5.0-arm64-v8a.apk"))
        assertEquals("0.4.10", Updater.versionOf("OpenTune-v0.4.10-universal.apk"))
        assertEquals("1.2", Updater.versionOf("OpenTune-v1.2-x86_64.apk"))
        assertEquals("0.4.18-custom.1", Updater.versionOf("OpenTune-v0.4.18-custom.1-universal.apk"))
        assertEquals("0.4.18-custom.10", Updater.versionOf("OpenTune-v0.4.18-custom.10-arm64-v8a.apk"))
        assertNull(Updater.versionOf("OpenTune-v0.4.18-custom.1-universal.apk.part"))
        assertNull(Updater.versionOf("OpenTune-v0.4.18-custom.foo-universal.apk"))
        assertNull(Updater.versionOf("OpenTune-v0.5.0-arm64-v8a.apk.part"))
        assertNull(Updater.versionOf("notes.txt"))
    }

    @Test fun readsSha256sumOutput() {
        val sums = """
            0f1e2d3c  OpenTune-v0.3.0-arm64-v8a.apk
            aabbccdd *OpenTune-v0.3.0-universal.apk
        """.trimIndent()
        assertEquals("0f1e2d3c", Updater.expectedSum(sums, "OpenTune-v0.3.0-arm64-v8a.apk"))
        assertEquals("aabbccdd", Updater.expectedSum(sums, "OpenTune-v0.3.0-universal.apk"))
        assertNull(Updater.expectedSum(sums, "OpenTune-v0.3.0-x86_64.apk"))
    }

    @Test fun readsTheLatestReleaseFromWhereTheReleasesPageLeads() {
        val r = UpdateCheck.fromPage("https://github.com/axmeliee/OpenTune-Custom/releases/tag/v0.4.18-custom.1", listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("0.4.18-custom.1", r.version)
        assertEquals("OpenTune-v0.4.18-custom.1-universal.apk", r.apk?.name)
        assertEquals("https://github.com/axmeliee/OpenTune-Custom/releases/tag/v0.4.18-custom.1", r.page)
        assertEquals("https://github.com/axmeliee/OpenTune-Custom/releases/download/v0.4.18-custom.1/OpenTune-v0.4.18-custom.1-universal.apk", r.apk?.url)
        assertEquals("https://github.com/axmeliee/OpenTune-Custom/releases/download/v0.4.18-custom.1/SHA256SUMS.txt", r.sums?.url)
        assertEquals(r.version, Updater.versionOf(r.apk!!.name))
        // Cached downloads of this version become obsolete once it is installed.
        assertFalse(UpdateCheck.isNewer(Updater.versionOf(r.apk!!.name)!!, "0.4.18-custom.1"))
        assertTrue(UpdateCheck.isNewer(Updater.versionOf(r.apk!!.name)!!, "0.4.17-custom.1"))
        // The HTML fallback always uses universal, even on a supported processor.
        assertEquals(r.apk?.name, UpdateCheck.fromPage(r.page, listOf("riscv64"))!!.apk?.name)
        // No release yet: the page stays on the release list.
        assertNull(UpdateCheck.fromPage(UpdateCheck.RELEASES_PAGE, listOf("arm64-v8a")))
    }

    @Test fun parsesCustomReleaseWithUniversalAssetOnly() {
        val page = "https://github.com/axmeliee/OpenTune-Custom/releases/tag/v0.4.18-custom.1"
        val url = "https://github.com/axmeliee/OpenTune-Custom/releases/download/v0.4.18-custom.1/OpenTune-v0.4.18-custom.1-universal.apk"
        val obj = Json.parseToJsonElement("""
            {"tag_name":"v0.4.18-custom.1","html_url":"$page","assets":[
              {"name":"OpenTune-v0.4.18-custom.1-universal.apk","browser_download_url":"$url","size":123},
              {"name":"SHA256SUMS.txt","browser_download_url":"https://x/sums","size":100}
            ]}
        """).jsonObject
        val r = UpdateCheck.parse(obj, listOf("arm64-v8a"))!!
        assertEquals("0.4.18-custom.1", r.version)
        assertEquals(page, r.page)
        assertEquals(url, r.apk?.url)
        assertEquals("SHA256SUMS.txt", r.sums?.name)
    }
}
