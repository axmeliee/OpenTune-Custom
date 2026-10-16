package com.opentune.data

import com.opentune.data.settings.SettingsState
import com.opentune.data.settings.WelcomeState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WelcomeTest {
    private val fresh = Welcome.Signals(plays = 0, libraryItems = 0, followedArtists = 0, downloads = 0, signedIn = false, updated = false)

    @Test fun aFreshInstallGetsTheWelcome() {
        assertEquals(WelcomeState.DUE, Welcome.decide(WelcomeState.UNDECIDED, fresh))
    }

    @Test fun anyoneAlreadyUsingTheAppSkipsItSilently() {
        listOf(
            fresh.copy(plays = 1),
            fresh.copy(libraryItems = 2),
            fresh.copy(followedArtists = 1),
            fresh.copy(downloads = 3),
            fresh.copy(signedIn = true),
            // Updated from an older version, even one never played from.
            fresh.copy(updated = true),
        ).forEach { s -> assertEquals(s.toString(), WelcomeState.SEEN, Welcome.decide(WelcomeState.UNDECIDED, s)) }
    }

    @Test fun theDecisionIsMadeOnce() {
        // A welcome left halfway has already followed artists; it still comes back.
        assertEquals(WelcomeState.DUE, Welcome.decide(WelcomeState.DUE, fresh.copy(followedArtists = 3, signedIn = true)))
        // Shown again from Settings by someone with years of history.
        assertEquals(WelcomeState.DUE, Welcome.decide(WelcomeState.DUE, fresh.copy(plays = 5000, updated = true)))
        assertEquals(WelcomeState.SEEN, Welcome.decide(WelcomeState.SEEN, fresh))
    }

    @Test fun settingsSavedBeforeTheWelcomeReadAsUndecided() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        assertEquals(WelcomeState.UNDECIDED, json.decodeFromString<SettingsState>("""{"ui":{"reduceAnimation":true}}""").ui.welcome)
        val seen = SettingsState().let { it.copy(ui = it.ui.copy(welcome = WelcomeState.SEEN)) }
        assertEquals(WelcomeState.SEEN, json.decodeFromString<SettingsState>(json.encodeToString(SettingsState.serializer(), seen)).ui.welcome)
    }
}
