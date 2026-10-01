package com.example.watchbridge.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the health payload chunker against the MTU sizes Galaxy watches
 * actually negotiate. android.org.json is stubbed in android.jar, so this runs
 * against a real implementation supplied by the test runtime.
 */
class HealthPayloadChunkTest {

    private fun samples(count: Int) = (0 until count).map { i ->
        HealthSample(
            kind = HealthSample.KIND_HEART_RATE,
            startMillis = 1_759_000_000_000L + i * 60_000L,
            endMillis = 1_759_000_060_000L + i * 60_000L,
            value = 60.0 + i,
            unit = HealthSample.UNIT_BPM
        )
    }

    private val sleep = listOf(
        SleepInterval(1_759_000_000_000L, 1_759_288_000_000L, SleepInterval.STAGE_ASLEEP)
    )

    @Test
    fun `every chunk fits the negotiated MTU`() {
        for (mtu in listOf(185, 247, 300, 512)) {
            val chunks = HealthPayload.chunk(samples(40), sleep, mtu)
            assertTrue("no chunks produced at MTU $mtu", chunks.isNotEmpty())
            for (chunk in chunks) {
                assertTrue(
                    "chunk of ${chunk.size}B exceeds usable ${mtu - 3}B at MTU $mtu",
                    chunk.size <= mtu - 3
                )
            }
        }
    }

    @Test
    fun `no sample is lost or duplicated`() {
        val input = samples(40)
        val chunks = HealthPayload.chunk(input, sleep, 185)
        val seen = mutableListOf<Long>()
        for (chunk in chunks) {
            val doc = JSONObject(String(chunk, Charsets.UTF_8)).getString("doc")
            val arr = JSONObject(doc).getJSONArray("samples")
            for (i in 0 until arr.length()) {
                seen.add(arr.getJSONObject(i).getLong("start"))
            }
        }
        assertEquals("sample count changed", input.size, seen.size)
        assertEquals("samples duplicated", input.size, seen.toSet().size)
    }

    @Test
    fun `sleep survives chunking`() {
        val chunks = HealthPayload.chunk(samples(10), sleep, 185)
        val total = chunks.sumOf { chunk ->
            JSONObject(String(chunk, Charsets.UTF_8)).getString("doc")
                .let { JSONObject(it).getJSONArray("sleep").length() }
        }
        assertEquals(1, total)
    }

    @Test
    fun `exactly one chunk is marked last`() {
        val chunks = HealthPayload.chunk(samples(40), sleep, 185)
        val lastCount = chunks.count { chunk ->
            JSONObject(String(chunk, Charsets.UTF_8)).getBoolean("last")
        }
        assertEquals(1, lastCount)
    }

    @Test
    fun `sequence numbers are ordered`() {
        val chunks = HealthPayload.chunk(samples(40), sleep, 185)
        val seqs = chunks.map {
            JSONObject(String(it, Charsets.UTF_8)).getInt("seq")
        }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(0, seqs.first())
    }
}
