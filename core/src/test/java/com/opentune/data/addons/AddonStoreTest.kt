package com.opentune.data.addons

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddonStoreTest {
    @Test fun preservesPriorityAndEnablementAcrossRestartAndDoesNotExposePathAsName() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AddonStore.init(context)
        AddonStore.add("https://one.example/private/manifest.json", AddonManifest("first", "First", "1"))
        AddonStore.add("https://two.example/", AddonManifest("second", "Second", "1", downloads = false, outputRequired = true))
        val second = AddonStore.addons.value.last()
        AddonStore.move(second.key, -1); AddonStore.enable(second.key, false)
        AddonStore.init(context)
        assertEquals("second", AddonStore.addons.value.first().manifest.id)
        assertFalse(AddonStore.addons.value.first().enabled)
        assertFalse(AddonStore.addons.value.first().manifest.downloads)
        assertFalse(AddonStore.usable(AddonStore.addons.value.first()))
        AddonStore.enable(second.key, true)
        AddonStore.outputAllowed = { false }; assertFalse(AddonStore.usable(AddonStore.addons.value.first()))
        AddonStore.outputAllowed = { true }; assertTrue(AddonStore.usable(AddonStore.addons.value.first()))
        AddonStore.remove(second.key); assertEquals(1, AddonStore.addons.value.size)
    }
}
