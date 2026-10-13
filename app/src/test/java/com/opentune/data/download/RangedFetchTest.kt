package com.opentune.data.download

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangedFetchTest {
    private val data = Random(7).nextBytes(5 * 1024 * 1024 + 12_345)
    private val ranges = Collections.synchronizedList(mutableListOf<LongRange>())
    private val dropped = AtomicBoolean(false)

    /** A server that answers Range requests from [data]; the second range is cut short once, as a dropped connection would be. */
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val (a, b) = request.header("Range")!!.removePrefix("bytes=").split('-').map { it.toLong() }
        val end = minOf(b, data.size - 1L)
        ranges += a..end
        val body = data.copyOfRange(a.toInt(), end.toInt() + 1)
        val cut = a == RangedFetch.CHUNK && dropped.compareAndSet(false, true)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
            .header("Content-Range", "bytes $a-$end/${data.size}")
            .body((if (cut) body.copyOf(body.size / 2) else body).toResponseBody())
            .build()
    }.build()

    @Test fun fetchesTheFileInPlayerSizedRanges() = runBlocking {
        val part = File.createTempFile("ranged", ".part")
        var last = 0f
        RangedFetch.fetch(client, "https://media.invalid/audio?clen=${data.size}", emptyMap(), part) { last = it }
        assertArrayEquals(data, part.readBytes())
        // No range larger than the player's, and the cut one was asked for again.
        assertTrue(ranges.all { it.last - it.first + 1 <= RangedFetch.CHUNK })
        assertTrue(ranges.count { it.first == RangedFetch.CHUNK } >= 2)
        assertTrue(last > 0.9f && last <= 1f)
        part.delete()
        Unit
    }

    @Test fun findsTheSizeWhenTheUrlDoesntSay() = runBlocking {
        dropped.set(true)
        val part = File.createTempFile("ranged", ".part")
        RangedFetch.fetch(client, "https://media.invalid/audio", emptyMap(), part) {}
        assertEquals(data.size.toLong(), part.length())
        assertArrayEquals(data, part.readBytes())
        part.delete()
        Unit
    }
}
