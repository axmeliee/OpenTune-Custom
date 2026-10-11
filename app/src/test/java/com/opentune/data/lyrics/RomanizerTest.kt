package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RomanizerTest {
    @Test fun otherScriptsInLatinLetters() {
        assertEquals("aja piya", Romanizer.romanize("आजा पिया"))
        assertEquals("saranghae", Romanizer.romanize("사랑해"))
        assertEquals("privet", Romanizer.romanize("привет"))
        // A final consonant carries into a silent ㅇ, and ㄹ next to ㄹ is "l".
        assertEquals("hangugeo", Romanizer.romanize("한국어"))
        assertEquals("ollae", Romanizer.romanize("올래"))
    }

    @Test fun latinLinesNeedNothing() {
        assertNull(Romanizer.romanize("I'm blinded by the lights"))
    }

    @Test fun kanjiStayInJapaneseLyrics() {
        val out = Romanizer.lines(listOf("君の名は", "さよなら"))
        assertEquals("sayonara", out[1])
        assertEquals(true, out[0]!!.startsWith("君"))
    }
}
