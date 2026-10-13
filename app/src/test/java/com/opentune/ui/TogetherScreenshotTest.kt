package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.data.together.Member
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together
import com.opentune.data.together.Track
import com.opentune.ui.together.RoomView
import com.opentune.ui.together.StartRoom
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the Listen together screens to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class TogetherScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65), primaryContainer = Color(0xFF5A2E22), onPrimaryContainer = Color(0xFFFFDBCF))) {
                androidx.compose.material3.Surface(color = Color(0xFF0B0B0E), contentColor = Color(0xFFECE6F0)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E))) { content() }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun start() = shoot("together-start") { StartRoom(code = null, onBack = {}) }

    @Test fun room() {
        val code = RoomCode.parse("K7QX-M2PA-9DTE")!!
        val room = Together.Room(
            code = code, hosting = true, myName = "Asha", phase = Together.Phase.Live, relays = 6,
            members = listOf(Member("a", "Asha", host = true), Member("b", "Ravi"), Member("c", "Meera")),
            hostName = "Asha", open = false,
            track = Track("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd"),
            chat = listOf(
                Together.ChatLine("", "Room open. Share the code so friends can join.", system = true),
                Together.ChatLine("", "Ravi joined", system = true),
                Together.ChatLine("Ravi", "this one's a classic"),
                Together.ChatLine("Asha", "turn it up", mine = true),
                Together.ChatLine("", "Meera added Levitating", system = true),
            ),
        )
        shoot("together-room") { RoomView(room, onBack = {}) }
    }

    /** The whole room on one tall screen: someone knocking, talk, and voice notes in the chat. */
    @Test @Config(sdk = [35], qualifiers = "w411dp-h2000dp-xxhdpi", application = android.app.Application::class)
    fun roomWithRequestsAndVoice() {
        val code = RoomCode.parse("K7QX-M2PA-9DTE")!!
        val note = Together.VoiceClip("b/n1", byteArrayOf(0x14, 0x08), listOf(ByteArray(10)), 12_000, complete = true, live = false)
        val talk = Together.VoiceClip("c/t1", byteArrayOf(0x14, 0x08), listOf(ByteArray(10)), 4_000, complete = true, live = true)
        val room = Together.Room(
            code = code, hosting = true, myName = "Asha", phase = Together.Phase.Live, relays = 6, myId = "a",
            members = listOf(Member("a", "Asha", host = true), Member("b", "Ravi"), Member("c", "Meera")),
            requests = listOf(Member("d", "Kabir")),
            hostName = "Asha", open = true,
            track = Track("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd"),
            chat = listOf(
                Together.ChatLine("", "Ravi joined", system = true, at = 1_760_000_000_000),
                Together.ChatLine("Ravi", "this one's a classic", at = 1_760_000_060_000),
                Together.ChatLine("Ravi", "", voice = note, at = 1_760_000_090_000),
                Together.ChatLine("Asha", "turn it up", mine = true, at = 1_760_000_120_000),
                Together.ChatLine("Meera", "", voice = talk, at = 1_760_000_150_000),
            ),
        )
        shoot("together-room-full") { RoomView(room, onBack = {}) }
    }
}
