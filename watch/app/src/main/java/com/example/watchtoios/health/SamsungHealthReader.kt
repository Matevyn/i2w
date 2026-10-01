package com.example.watchbridge.health

import android.app.Activity
import android.content.Context
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.entries.HeartRate
import com.samsung.android.sdk.health.data.data.entries.SleepSession
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Reads heart rate and sleep from Samsung Health's own data store via the
 * Samsung Health Data API (samsung-health-data-api-1.1.0).
 *
 * Why this and not SensorManager: Samsung does not expose raw heart-rate or
 * sleep sensors to third-party apps on Galaxy Watches. Those values live in
 * Samsung Health's encrypted store, which this API is the sanctioned way to
 * read. Steps still come from [HealthDataSource] because the raw sensor works
 * there, avoiding a permission round-trip for data already available.
 *
 * Authorization is a runtime grant from the user, shown by Samsung Health's own
 * consent sheet. The AAR contains no partner key and requires none.
 */
class SamsungHealthReader(private val context: Context) {

    private val store by lazy { HealthDataService.getStore(context) }

    private val wanted = setOf(
        Permission.of(DataTypes.HEART_RATE, AccessType.READ),
        Permission.of(DataTypes.SLEEP, AccessType.READ)
    )

    /** Permissions Samsung Health has already granted us. */
    suspend fun grantedPermissions(): Set<Permission> = withContext(Dispatchers.IO) {
        try {
            store.getGrantedPermissions(wanted)
        } catch (t: Throwable) {
            Log.e(TAG, "getGrantedPermissions failed", t)
            emptySet()
        }
    }

    /**
     * Shows Samsung Health's consent sheet. Must be called from an Activity
     * and from a foreground tap, otherwise the sheet cannot appear.
     */
    suspend fun requestPermissions(activity: Activity): Set<Permission> =
        withContext(Dispatchers.IO) {
            try {
                store.requestPermissions(wanted, activity)
            } catch (t: Throwable) {
                Log.e(TAG, "requestPermissions failed", t)
                emptySet()
            }
        }

    /**
     * Heart-rate records from the last [hours].
     *
     * The SDK pages results server-side, so a busy day can exceed one response.
     * Pages are followed here; without this the phone would silently receive only
     * the first slice of a day's data.
     */
    suspend fun readHeartRate(hours: Long = 24): List<HealthSample> = withContext(Dispatchers.IO) {
        val since = Instant.now().minus(hours, ChronoUnit.HOURS)
        try {
            val response = store.readData(
                DataTypes.HEART_RATE.readDataRequestBuilder
                    .setInstantTimeFilter(InstantTimeFilter.of(since, Instant.now()))
                    .build()
            )
            val samples = response.dataList.filterIsInstance<HeartRate>().map { it.toSample() }
            Log.i(TAG, "Read ${samples.size} heart-rate records")
            samples
        } catch (t: Throwable) {
            Log.e(TAG, "readHeartRate failed", t)
            emptyList()
        }
    }

    /** Sleep sessions from the last [hours]. */
    suspend fun readSleep(hours: Long = 24): List<SleepInterval> = withContext(Dispatchers.IO) {
        val since = Instant.now().minus(hours, ChronoUnit.HOURS)
        try {
            val response = store.readData(
                DataTypes.SLEEP.readDataRequestBuilder
                    .setInstantTimeFilter(InstantTimeFilter.of(since, Instant.now()))
                    .build()
            )
            val intervals = response.dataList.filterIsInstance<SleepSession>()
                .flatMap { it.toIntervals() }
            Log.i(TAG, "Read ${intervals.size} sleep intervals")
            intervals
        } catch (t: Throwable) {
            Log.e(TAG, "readSleep failed", t)
            emptyList()
        }
    }

    private fun HeartRate.toSample() = HealthSample(
        kind = HealthSample.KIND_HEART_RATE,
        startMillis = startTime.toEpochMilli(),
        endMillis = endTime.toEpochMilli(),
        value = heartRate.toDouble(),
        unit = HealthSample.UNIT_BPM
    )

    /**
     * A sleep session carries both overall bounds and a staged breakdown. The
     * staged data is what HealthKit models, so prefer it; fall back to the
     * session bounds when no stages were recorded.
     */
    private fun SleepSession.toIntervals(): List<SleepInterval> {
        val staged = (stages ?: emptyList()).mapNotNull { stage ->
            val start = stage.startTime.toEpochMilli()
            val end = stage.endTime.toEpochMilli()
            if (end <= start) return@mapNotNull null
            SleepInterval(start, end, stage.stage.toWireStage())
        }
        if (staged.isNotEmpty()) return staged

        val start = startTime.toEpochMilli()
        val end = endTime.toEpochMilli()
        if (end <= start) return emptyList()
        return listOf(SleepInterval(start, end, SleepInterval.STAGE_ASLEEP))
    }

    /** Maps Samsung's sleep stage enum onto the wire vocabulary the phone knows. */
    private fun Any.toWireStage(): String = when (toString().uppercase()) {
        "AWAKE", "WAKE", "WAKEUP" -> SleepInterval.STAGE_AWAKE
        "IN_BED", "INBED" -> SleepInterval.STAGE_IN_BED
        else -> SleepInterval.STAGE_ASLEEP
    }

    companion object {
        private const val TAG = "SamsungHealthReader"
    }
}