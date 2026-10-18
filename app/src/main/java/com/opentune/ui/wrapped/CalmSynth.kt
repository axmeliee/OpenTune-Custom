package com.opentune.ui.wrapped

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Soft music made on the phone for the Wrapped story: four slow chords that
 * melt into each other, a low note under them, now and then a bell-like note
 * from the same key, and a little room around it all. Nothing is downloaded
 * or streamed, so it always plays and belongs to no one.
 *
 * [render] fills a buffer with stereo samples, left and right in turn, each
 * between -1 and 1; it carries on from where the last call stopped.
 */
internal class CalmSynth(private val rate: Int = 44_100, seed: Long = 7L) {
    private val random = Random(seed)

    /** One sine cycle with a touch of its 2nd and 3rd harmonics, so the pad is warm rather than hollow. */
    private val table = FloatArray(TABLE + 1) { i ->
        val p = 2 * PI * i / TABLE
        ((sin(p) + 0.18 * sin(2 * p) + 0.05 * sin(3 * p)) / 1.12).toFloat()
    }

    private fun wave(phase: Double): Float {
        val at = phase * TABLE
        val i = at.toInt()
        val f = (at - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * f
    }

    private class Osc(val step: Double, var phase: Double, val left: Float, val right: Float)

    /** Each chord's notes, two slightly detuned voices apiece, one leaning left and one right. */
    private val chordVoices: List<List<Osc>> = CHORDS.map { notes ->
        notes.flatMap { n ->
            val hz = hz(n.toDouble())
            listOf(
                Osc(hz * CENTS_UP / rate, random.nextDouble(), 0.75f, 0.35f),
                Osc(hz / CENTS_UP / rate, random.nextDouble(), 0.35f, 0.75f),
            )
        }
    }
    private val bassVoices: List<Osc> = CHORDS.map { Osc(hz(it[0] - 12.0) / rate, 0.0, 0.6f, 0.6f) }

    private class Bell(val step: Double, var phase: Double, val left: Float, val right: Float, var age: Int)
    private val bells = ArrayList<Bell>()
    private var nextBell = (2.5 * rate).toInt()

    private var t = 0L
    private var lowL = 0f
    private var lowR = 0f
    private val roomL = Room(0, rate)
    private val roomR = Room(STEREO_SPREAD, rate)

    fun render(out: FloatArray, frames: Int) {
        val chordLen = (CHORD_SECONDS * rate).toLong()
        val blend = (BLEND_SECONDS * rate).toLong()
        for (f in 0 until frames) {
            val pos = t % (chordLen * CHORDS.size)
            val current = (pos / chordLen).toInt()
            val into = pos % chordLen
            val next = (current + 1) % CHORDS.size
            // The last BLEND seconds of each chord fade over into the next.
            val over = if (into > chordLen - blend) smooth((into - (chordLen - blend)).toFloat() / blend) else 0f
            val seconds = t.toDouble() / rate

            var l = 0f
            var r = 0f
            for (k in 0..1) {
                val c = if (k == 0) current else next
                val gain = if (k == 0) 1f - over else over
                if (gain <= 0f) continue
                chordVoices[c].forEachIndexed { i, o ->
                    // Each note breathes on its own slow cycle.
                    val swell = 0.85f + 0.15f * sin(2 * PI * (0.07 + 0.013 * i) * seconds + i).toFloat()
                    val s = wave(o.phase) * gain * swell
                    l += s * o.left
                    r += s * o.right
                    o.phase += o.step
                    if (o.phase >= 1.0) o.phase -= 1.0
                }
                val b = bassVoices[c]
                val s = sin(2 * PI * b.phase).toFloat() * gain * BASS
                l += s * b.left
                r += s * b.right
                b.phase += b.step
                if (b.phase >= 1.0) b.phase -= 1.0
            }
            // A soft low-pass whose opening rises and falls over half a minute.
            val open = 0.06f + 0.035f * sin(2 * PI * seconds / 31.0).toFloat()
            lowL += (l * PAD - lowL) * open
            lowR += (r * PAD - lowR) * open

            var bl = 0f
            var br = 0f
            if (--nextBell <= 0) {
                strike(current)
                nextBell = ((1.8 + random.nextDouble() * 2.8) * rate).toInt()
            }
            val iter = bells.iterator()
            while (iter.hasNext()) {
                val b = iter.next()
                // A rolled note waits its turn before it sounds.
                if (b.age < 0) { b.age++; continue }
                val age = b.age.toDouble() / rate
                val env = (minOf(1.0, age / 0.012) * exp(-age / 1.7)).toFloat()
                if (age > 7.0) { iter.remove(); continue }
                val p = 2 * PI * b.phase
                val s = (sin(p) + 0.22 * sin(2 * p) * exp(-age / 0.5)).toFloat() * env * BELL
                bl += s * b.left
                br += s * b.right
                b.phase += b.step
                if (b.phase >= 1.0) b.phase -= 1.0
                b.age++
            }

            val dryL = lowL + bl
            val dryR = lowR + br
            val wetL = roomL.process(dryL * 0.5f + dryR * 0.5f)
            val wetR = roomR.process(dryL * 0.5f + dryR * 0.5f)
            out[2 * f] = tanh(((dryL * DRY + wetL * WET) * MASTER).toDouble()).toFloat()
            out[2 * f + 1] = tanh(((dryR * DRY + wetR * WET) * MASTER).toDouble()).toFloat()
            t++
        }
    }

    /** A bell on a note of the key, high above the chord, somewhere left or right. */
    private fun strike(chord: Int) {
        if (bells.size >= MAX_BELLS) bells.removeAt(0)
        val note = SCALE[random.nextInt(SCALE.size)] + 12 * random.nextInt(2)
        val pan = random.nextFloat() * 1.2f - 0.6f
        bells += Bell(hz(note.toDouble()) / rate, 0.0, 0.7f - pan * 0.5f, 0.7f + pan * 0.5f, 0)
        // A second, quieter note a step of the chord away, now and then, as if a hand rolled it.
        if (random.nextFloat() < 0.35f && bells.size < MAX_BELLS) {
            val other = CHORDS[chord][2 + random.nextInt(CHORDS[chord].size - 2)] + 12
            bells += Bell(hz(other.toDouble()) / rate, 0.0, 0.7f + pan * 0.4f, 0.7f - pan * 0.4f, -(0.22 * rate).toInt())
        }
    }

    /**
     * A small room: four feedback echoes in parallel, gently dulled each time
     * round, then two that smear them, the usual way to make a soft reverb.
     */
    private class Room(spread: Int, rate: Int) {
        private val scale = rate / 44_100f
        private val combs = intArrayOf(1557, 1617, 1491, 1422).map { Comb(((it + spread) * scale).toInt()) }
        private val passes = intArrayOf(556, 441).map { Pass(((it + spread) * scale).toInt()) }

        fun process(x: Float): Float {
            var s = 0f
            for (c in combs) s += c.process(x * 0.25f)
            for (p in passes) s = p.process(s)
            return s
        }

        private class Comb(size: Int) {
            private val buf = FloatArray(size)
            private var i = 0
            private var store = 0f
            fun process(x: Float): Float {
                val y = buf[i]
                store = y * 0.7f + store * 0.3f
                buf[i] = x + store * 0.86f
                if (++i == buf.size) i = 0
                return y
            }
        }

        private class Pass(size: Int) {
            private val buf = FloatArray(size)
            private var i = 0
            fun process(x: Float): Float {
                val b = buf[i]
                buf[i] = x + b * 0.5f
                if (++i == buf.size) i = 0
                return b - x
            }
        }
    }

    companion object {
        private const val TABLE = 4096
        const val CHORD_SECONDS = 9.0
        const val BLEND_SECONDS = 4.0
        private const val MAX_BELLS = 6
        private val CENTS_UP = 2.0.pow(4.0 / 1200)
        private const val PAD = 0.075f
        private const val BASS = 0.32f
        private const val BELL = 0.11f
        private const val DRY = 0.8f
        private const val WET = 0.55f
        private const val MASTER = 1.1f
        private const val STEREO_SPREAD = 23

        /** D major, slowly: Dmaj9, Bm11, Gmaj7, A6/9, as MIDI notes from the bass up. */
        val CHORDS = listOf(
            intArrayOf(50, 57, 62, 66, 69, 76),
            intArrayOf(47, 54, 61, 62, 66, 69),
            intArrayOf(43, 50, 57, 59, 62, 66),
            intArrayOf(45, 52, 57, 61, 64, 71),
        )

        /** The bells' notes: D major's five-note scale, high up. */
        private val SCALE = intArrayOf(74, 76, 78, 81, 83)

        fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12)

        private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    }
}
