package com.example.watchbridge.health

import org.json.JSONArray
import org.json.JSONObject

/**
 * One reading that we intend to push to the iPhone and then into Apple Health.
 *
 * [kind] must match one of the constants below. The iPhone side switches on it and
 * maps each to a concrete HealthKit type.
 */
data class HealthSample(
    val kind: String,
    val startMillis: Long,
    val endMillis: Long,
    val value: Double,
    val unit: String
) {
    companion object {
        const val KIND_STEP_COUNT = "stepCount"
        const val KIND_HEART_RATE = "heartRate"

        // HealthKit wants the value in its own canonical unit; we always transmit
        // these two units so the phone never has to guess.
        const val UNIT_COUNT = "count"
        const val UNIT_BPM = "count/min"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("start", startMillis)
        put("end", endMillis)
        put("value", value)
        put("unit", unit)
    }
}

/**
 * Sleep as a single interval. Apple Health models sleep as a series of staged
 * samples rather than one interval, so this stays simple on the wire and the
 * iPhone expands it.
 */
data class SleepInterval(
    val startMillis: Long,
    val endMillis: Long,
    val stage: String = "asleep"
) {
    companion object {
        const val STAGE_ASLEEP = "asleep"
        const val STAGE_AWAKE = "awake"
        const val STAGE_IN_BED = "inBed"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("start", startMillis)
        put("end", endMillis)
        put("stage", stage)
    }
}

/**
 * Wire format for a batch of health data, plus the chunking needed to get it
 * through a BLE link.
 *
 * A single notification cannot carry more than the negotiated MTU, and a batch
 * of a few dozen samples routinely exceeds that. Chunks are wrapped in an
 * envelope carrying a sequence marker and a last-chunk flag so the phone knows
 * when it holds a complete document; without one it would try to decode every
 * fragment and fail on all of them.
 */
object HealthPayload {

    const val VERSION = 1

    private const val KEY_SEQ = "seq"
    private const val KEY_LAST = "last"
    private const val KEY_DOC = "doc"

    fun encode(samples: List<HealthSample>, sleep: List<SleepInterval>): ByteArray {
        val arr = JSONArray()
        samples.forEach { arr.put(it.toJson()) }

        val sleepArr = JSONArray()
        sleep.forEach { sleepArr.put(it.toJson()) }

        val root = JSONObject().apply {
            put("v", VERSION)
            put("samples", arr)
            put("sleep", sleepArr)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Splits a batch into notifications no larger than [maxPayloadBytes].
     *
     * Samples are grouped rather than raw bytes cut mid-token: a fragment that
     * splits a JSON value is unrecoverable, whereas a smaller batch is always a
     * valid document. Every chunk therefore carries a complete document, and the
     * phone writes each one as it arrives.
     *
     * The fit test encodes the real envelope rather than assuming a fixed
     * overhead. A guessed constant of 48 bytes was larger than a single heart-
     * rate sample's entire envelope, which made the whole batch silently
     * untransmittable at the 185-byte MTU that Galaxy watches commonly
     * negotiate.
     *
     * A batch that cannot fit even one sample is dropped rather than truncated,
     * because a partial step count written into Apple Health is worse than none.
     */
    fun chunk(
        samples: List<HealthSample>,
        sleep: List<SleepInterval>,
        maxPayloadBytes: Int
    ): List<ByteArray> {
        if (samples.isEmpty() && sleep.isEmpty()) return emptyList()

        // 3 bytes go to the ATT notification header.
        val usable = maxPayloadBytes - ATT_HEADER_BYTES
        if (usable <= 0) return emptyList()

        val groups = mutableListOf<Pair<List<HealthSample>, List<SleepInterval>>>()
        var currentSamples = mutableListOf<HealthSample>()
        var currentSleep = mutableListOf<SleepInterval>()

        // Measured, not estimated.
        fun fits(): Boolean = envelopeLength(currentSamples, currentSleep) <= usable

        fun flush() {
            if (currentSamples.isNotEmpty() || currentSleep.isNotEmpty()) {
                groups.add(currentSamples.toList() to currentSleep.toList())
            }
            currentSamples = mutableListOf()
            currentSleep = mutableListOf()
        }

        for (sample in samples) {
            currentSamples.add(sample)
            if (!fits()) {
                currentSamples.removeAt(currentSamples.size - 1)
                flush()
                currentSamples.add(sample)
                if (!fits()) return emptyList()
            }
        }
        for (interval in sleep) {
            currentSleep.add(interval)
            if (!fits()) {
                currentSleep.removeAt(currentSleep.size - 1)
                flush()
                currentSleep.add(interval)
            }
        }
        flush()

        val lastIndex = groups.lastIndex
        return groups.mapIndexed { index, (groupSamples, groupSleep) ->
            envelope(index, index == lastIndex, groupSamples, groupSleep)
        }
    }

    private const val ATT_HEADER_BYTES = 3

    /// Encoded size of the full envelope for a candidate group.
    private fun envelopeLength(
        samples: List<HealthSample>,
        sleep: List<SleepInterval>
    ): Int = envelope(0, true, samples, sleep).size

    private fun envelope(
        index: Int,
        last: Boolean,
        samples: List<HealthSample>,
        sleep: List<SleepInterval>
    ): ByteArray {
        val doc = JSONObject().apply {
            put("v", VERSION)
            put("samples", JSONArray().apply { samples.forEach { put(it.toJson()) } })
            put("sleep", JSONArray().apply { sleep.forEach { put(it.toJson()) } })
        }.toString()

        return JSONObject().apply {
            put(KEY_SEQ, index)
            put(KEY_LAST, last)
            put(KEY_DOC, doc)
        }.toString().toByteArray(Charsets.UTF_8)
    }
}