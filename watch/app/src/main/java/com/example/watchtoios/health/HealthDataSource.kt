package com.example.watchbridge.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Reads health data off the watch and hands it back as [HealthSample]s.
 *
 * IMPORTANT, and the reason this class is shaped like an interface rather than a
 * straight SensorManager dump: on Galaxy Watches only *step* data is reachable
 * through public Android APIs. Heart rate and sleep are deliberately not
 * implemented here, because Samsung does not expose them to third-party apps
 * without a Samsung Partner / Health Data Access API grant. Requesting them
 * would produce a permission prompt that can never be satisfied.
 *
 * [availability] reports what this device actually supports so the UI can say so
 * instead of silently sending nothing.
 */
class HealthDataSource(private val context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val stepCounter: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val stepDetector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val currentListeners = mutableMapOf<Kind, MutableList<(HealthSample) -> Unit>>()

    private var lastStepTotal: Float? = null
    private var stepWindowStart: Long = 0L

    /** What this specific watch can actually provide, determined at runtime. */
    data class Availability(
        val steps: Boolean,
        val heartRate: Boolean,
        val sleep: Boolean
    ) {
        val anySupported: Boolean get() = steps || heartRate || sleep

        fun describe(): String = buildList {
            add("kroky=" + if (steps) "ano" else "nie")
            add("tep=" + if (heartRate) "ano" else "nie (blokovane Samsungom)")
            add("spánok=" + if (sleep) "ano" else "nie (pouzivatelske data)")
        }.joinToString(", ")
    }

    private val availability = Availability(
        steps = stepCounter != null || stepDetector != null,
        // Kept false deliberately: Galaxy watches refuse direct heart-rate sensor
        // access to apps without the Samsung Partner grant. See class docs.
        heartRate = false,
        sleep = false
    )

    enum class Kind { STEPS, HEART_RATE, SLEEP }

    fun availability(): Availability = availability

    /**
     * Begins delivering readings of [kind]. Returns false when this watch cannot
     * provide that kind at all, so callers can report it rather than hanging.
     */
    fun start(kind: Kind, onSample: (HealthSample) -> Unit): Boolean {
        val sensor = when (kind) {
            Kind.STEPS -> stepCounter ?: stepDetector ?: return false
            Kind.HEART_RATE -> return false // blocked, see class docs
            Kind.SLEEP -> return false      // blocked, see class docs
        }

        val existed = currentListeners.putIfAbsent(kind, mutableListOf()) != null
        currentListeners.getValue(kind).add(onSample)

        if (!existed) {
            // SENSOR_DELAY_NORMAL is plenty: step totals only need to be accurate
            // per batch window, not per event.
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Registered listener for $kind (${sensor.name})")
        }
        return true
    }

    fun stop(kind: Kind) {
        val listeners = currentListeners.remove(kind) ?: return
        if (listeners.isEmpty()) {
            val sensor = when (kind) {
                Kind.STEPS -> stepCounter ?: stepDetector
                else -> null
            }
            sensor?.let {
                sensorManager.unregisterListener(this, it)
                Log.i(TAG, "Unregistered listener for $kind")
            }
        }
    }

    fun stopAll() {
        sensorManager.unregisterListener(this)
        currentListeners.clear()
        lastStepTotal = null
        stepWindowStart = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> handleStepCounter(event)
            Sensor.TYPE_STEP_DETECTOR -> handleStepDetector(event)
        }
    }

    /**
     * TYPE_STEP_COUNTER reports steps since the watch last rebooted, so a delta
     * against the previous reading gives steps within this window.
     */
    private fun handleStepCounter(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val total = event.values.firstOrNull() ?: return
        val previous = lastStepTotal

        if (previous == null) {
            // First reading only establishes a baseline; there is nothing to report yet.
            lastStepTotal = total
            stepWindowStart = now
            Log.i(TAG, "Baseline step counter: $total")
            return
        }

        val delta = total - previous
        if (delta < 0f) {
            // Counter reset (reboot). Re-baseline rather than reporting nonsense.
            lastStepTotal = total
            stepWindowStart = now
            return
        }

        val start = stepWindowStart
        lastStepTotal = total
        stepWindowStart = now

        if (delta <= 0f) return

        emit(
            HealthSample(
                kind = HealthSample.KIND_STEP_COUNT,
                startMillis = start,
                endMillis = now,
                value = delta.toDouble(),
                unit = HealthSample.UNIT_COUNT
            )
        )
    }

    /**
     * TYPE_STEP_DETECTOR fires once per step, so we batch into fixed windows.
     * This is the fallback path on watches that lack a cumulative counter.
     */
    private fun handleStepDetector(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (stepWindowStart == 0L) stepWindowStart = now

        val delta = event.values.firstOrNull() ?: return
        if (delta <= 0f) return

        emit(
            HealthSample(
                kind = HealthSample.KIND_STEP_COUNT,
                startMillis = stepWindowStart,
                endMillis = now,
                value = delta.toDouble(),
                unit = HealthSample.UNIT_COUNT
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun emit(sample: HealthSample) {
        val kind = when (sample.kind) {
            HealthSample.KIND_STEP_COUNT -> Kind.STEPS
            HealthSample.KIND_HEART_RATE -> Kind.HEART_RATE
            else -> return
        }
        currentListeners[kind]?.toList()?.forEach { it(sample) }
    }

    companion object {
        private const val TAG = "HealthDataSource"
    }
}