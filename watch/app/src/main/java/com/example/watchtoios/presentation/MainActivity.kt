package com.example.watchbridge.presentation

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.ArcProgressIndicator
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchbridge.health.HealthDataSource
import com.example.watchbridge.health.HealthGattBridge
import com.example.watchbridge.health.HealthSample
import com.example.watchbridge.health.SamsungHealthReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val tagLog = "WatchBLE"

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val CHARACTERISTIC_UUID = UUID.fromString("87654321-4321-4321-4321-cba987654321")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var bluetoothGattServer: BluetoothGattServer? = null

    private var statusText by mutableStateOf("Spúšťanie...")
    private var bitmapState by mutableStateOf<Bitmap?>(null)
    private var healthText by mutableStateOf("")

    // Transfer progress, surfaced in the UI as an arc. Without it the watch looks
    // frozen for the whole time a large photo is arriving, because the photo
    // itself only appears once decoding has finished.
    private var isReceivingPhoto by mutableStateOf(false)
    private var photoProgress by mutableStateOf(0f)

    // --- Zdravotné údaje ---
    private lateinit var healthSource: HealthDataSource
    private var healthBridge: HealthGattBridge? = null

    // Readings are batched rather than sent per event: a notify per step would
    // hammer the GATT link, and a watch asleep for an hour can accumulate a lot
    // of samples that fit in one MTU-sized payload.
    private val pendingSamples = mutableListOf<HealthSample>()
    private var lastFlushAt = 0L

    private val samsungReader by lazy { SamsungHealthReader(applicationContext) }
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate)

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val imageBuffer = ByteArrayOutputStream()
    private var expectedSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    WatchScreen(
                        statusText = statusText,
                        bitmap = bitmapState,
                        healthText = healthText,
                        isSendingPhoto = isReceivingPhoto,
                        photoProgress = photoProgress,
                        onRequestSamsungHealth = { requestSamsungHealth() },
                        onSyncHealth = { syncSamsungHealth() }
                    )
                }
            }

            checkAndRequestPermissions()

            healthSource = HealthDataSource(applicationContext)
            healthText = "Zdravie: ${healthSource.availability().describe()}"
            startHealthStreaming()

        } catch (t: Throwable) {
            Log.e(tagLog, "CRITICAL CRASH v onCreate: ", t)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.ACTIVITY_RECOGNITION
            )
        } else {
            arrayOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            statusText = "Žiadám o povolenia..."
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 101)
        } else {
            initBluetoothAndStart()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initBluetoothAndStart()
            } else {
                statusText = "Chýbajú povolenia!"
            }
        }
    }

    private fun startHealthStreaming() {
        // Only start what this watch actually supports; the unavailable kinds
        // report false and are skipped instead of looping on a missing sensor.
        val started = healthSource.start(HealthDataSource.Kind.STEPS) { sample ->
            synchronized(pendingSamples) {
                pendingSamples.add(sample)
                // Flush on a timer rather than per sample. The bridge drops the
                // batch when no phone is subscribed, so buffering is safe.
                if (System.currentTimeMillis() - lastFlushAt > FLUSH_INTERVAL_MS) {
                    flushHealth()
                }
            }
        }

        if (!started) {
            healthText = "Zdravie: kroky nedostupné"
            Log.w(tagLog, "Step sensor unavailable")
        }
    }

    /** Sends buffered samples to the phone. Safe to call when nothing is pending. */
    private fun flushHealth() {
        val batch: List<HealthSample>
        synchronized(pendingSamples) {
            if (pendingSamples.isEmpty()) return
            batch = ArrayList(pendingSamples)
            pendingSamples.clear()
            lastFlushAt = System.currentTimeMillis()
        }
        val sent = healthBridge?.send(batch, emptyList())
        Log.i(tagLog, "Flush: ${batch.size} samples, sent=$sent")
    }

    /**
     * Shows Samsung Health's consent sheet for heart rate and sleep, then pulls
     * whatever was just authorised. Must be reached from a user tap: the sheet
     * cannot be presented without foreground interaction.
     */
    private fun requestSamsungHealth() {
        statusText = "Pýtam povolenie Samsung Health..."
        uiScope.launch {
            val granted = samsungReader.requestPermissions(this@MainActivity)
            healthText = if (granted.isEmpty()) {
                "Samsung Health: povolenie neudelené"
            } else {
                "Samsung Health: povolené (${granted.size})"
            }
            syncSamsungHealth()
        }
    }

    /** Pulls heart rate and sleep from Samsung Health into the outgoing batch. */
    private fun syncSamsungHealth() {
        uiScope.launch {
            statusText = "Načítavam tep a spánok..."
            val heartRate = samsungReader.readHeartRate(hours = SYNC_WINDOW_HOURS)
            val sleep = samsungReader.readSleep(hours = SYNC_WINDOW_HOURS)

            if (heartRate.isEmpty() && sleep.isEmpty()) {
                healthText = "Samsung Health: žiadne nové údaje"
                return@launch
            }

            // Send the Samsung data directly and leave it out of pendingSamples.
            // Adding it there as well made flushHealth send every heart-rate
            // record a second time, so Apple Health got duplicates.
            val sent = healthBridge?.send(heartRate, sleep)
            Log.i(tagLog, "Samsung sync: ${heartRate.size} HR, ${sleep.size} sleep, sent=$sent")

            healthText = "HR: ${heartRate.size}, spánok: ${sleep.size} sp. dát"
        }
    }

    /**
     * A transfer that stops mid-way leaves the buffer holding a partial photo
     * forever, so the next START-less chunk run would append to it. Clear on a
     * timer once no bytes have arrived for a while.
     */
    private val receiveTimeout = Runnable {
        if (isReceivingPhoto) {
            Log.w(tagLog, "Photo transfer timed out, discarding ${imageBuffer.size()} bytes")
            imageBuffer.reset()
            expectedSize = 0
            isReceivingPhoto = false
            photoProgress = 0f
            statusText = "Prenos fotky vypršal. Skús to znova."
        }
    }

    private fun scheduleReceiveTimeout() {
        handler.removeCallbacks(receiveTimeout)
        handler.postDelayed(receiveTimeout, RECEIVE_TIMEOUT_MS)
    }

    private fun cancelReceiveTimeout() {
        handler.removeCallbacks(receiveTimeout)
    }

    private fun initBluetoothAndStart() {
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter

            if (bluetoothAdapter == null) {
                statusText = "Bluetooth nepodporovaný!"
                return
            }

            if (!bluetoothAdapter!!.isEnabled) {
                statusText = "Zapni Bluetooth!"
                return
            }

            setupGattServer()
            startAdvertising()
        } catch (e: Exception) {
            statusText = "Chyba: ${e.message}"
            Log.e(tagLog, "Chyba v initBluetoothAndStart", e)
        }
    }

    private fun setupGattServer() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        val gattCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                super.onConnectionStateChange(device, status, newState)
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    statusText = "iPhone pripojený!\nČakám na dáta..."
                    // The phone may have subscribed before we were ready to flush.
                    flushHealth()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    statusText = "iPhone odpojený.\nČakám znova..."
                    healthBridge?.onSubscriptionChanged(device, false)
                }
            }

            /**
             * The phone subscribes to health notifications by writing the CCCD.
             * This is the only place that write ever arrives, so without this
             * override the watch never learns the phone is listening and no
             * health data is ever sent.
             */
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                descriptor: BluetoothGattDescriptor?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                super.onDescriptorWriteRequest(
                    device, requestId, descriptor, preparedWrite, responseNeeded, offset, value
                )

                val isCccd = descriptor?.uuid
                    ?.equals(HealthGattBridge.UUID_CLIENT_CHARACTERISTIC_CONFIG) == true

                if (isCccd) {
                    // Bit 0 of the low byte selects notifications.
                    val enable = value != null && value.size >= 2 && (value[0].toInt() and 0xff) == 1
                    healthBridge?.onSubscriptionChanged(device, enable)
                    if (enable) flushHealth()
                }

                if (responseNeeded) {
                    try {
                        bluetoothGattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                        )
                    } catch (e: SecurityException) {
                        Log.e(tagLog, "CCCD response error", e)
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                super.onMtuChanged(device, mtu)
                healthBridge?.maxPayloadBytes = mtu
                Log.i(tagLog, "MTU negotiated: $mtu")
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

                if (responseNeeded) {
                    try {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    } catch (e: SecurityException) {
                        Log.e(tagLog, "Gatt error", e)
                    }
                }

                // Note: CCCD writes arrive via onDescriptorWrite, not here. This handler is
                // for characteristic values only, which are photo bytes.

                if (characteristic?.uuid == CHARACTERISTIC_UUID && value != null) {
                    val message = String(value, Charsets.UTF_8)

                    // Re-arm the stall timer on every chunk that arrives.
                    scheduleReceiveTimeout()

                    if (message.startsWith("START:")) {
                        val sizeStr = message.substringAfter("START:")
                        expectedSize = sizeStr.toIntOrNull() ?: 0
                        imageBuffer.reset()
                        bitmapState = null
                        isReceivingPhoto = expectedSize > 0
                        photoProgress = 0f
                        statusText = "Prijímam originál...\n(0%)"
                    } else if (message == "END") {
                        cancelReceiveTimeout()
                        statusText = "Spracovávam fotku..."

                        // Spracovanie fotky na pozadí, aby hodinky pri megabajtovom súbore nezamrzli
                        Thread {
                            try {
                                val finalData = imageBuffer.toByteArray()
                                val bitmap = BitmapFactory.decodeByteArray(finalData, 0, finalData.size)

                                if (bitmap != null) {
                                    val savedUri = saveImageToGallery(bitmap)

                                    runOnUiThread {
                                        bitmapState = bitmap
                                        isReceivingPhoto = false
                                        photoProgress = 1f
                                        if (savedUri != null) {
                                            statusText = "Originál uložený!"
                                        } else {
                                            statusText = "Zobrazené bez uloženia."
                                        }
                                    }
                                } else {
                                    runOnUiThread {
                                        isReceivingPhoto = false
                                        statusText = "Chyba dekódovania!"
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(tagLog, "Chyba spracovania na pozadí", e)
                                runOnUiThread { statusText = "Chyba spracovania!" }
                            }
                        }.start()

                    } else {
                        imageBuffer.write(value)
                        if (expectedSize > 0) {
                            val fraction =
                                (imageBuffer.size().toFloat() / expectedSize.toFloat()).coerceIn(0f, 1f)
                            val progress = (fraction * 100).toInt()
                            runOnUiThread {
                                photoProgress = fraction
                                statusText = "Prijímam fotku...\n($progress%)"
                            }
                        }
                    }
                }
            }
        }

        try {
            bluetoothGattServer = bluetoothManager.openGattServer(this, gattCallback)
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(characteristic)

            // Health data rides a separate NOTIFY characteristic, subscribed to by
            // the iPhone. Same service, different characteristic, so the photo
            // protocol is untouched.
            val bridge = HealthGattBridge(
                bluetoothGattServer!!,
                SERVICE_UUID,
                HealthGattBridge.HEALTH_CHARACTERISTIC_UUID
            )
            bridge.attach(service)
            healthBridge = bridge

            bluetoothGattServer?.addService(service)
        } catch (e: SecurityException) {
            statusText = "GATT Security Error"
        }
    }

    private fun saveImageToGallery(bitmap: Bitmap): android.net.Uri? {
        val filename = "iOS_Original_${System.currentTimeMillis()}.jpg"
        var writeStream: OutputStream? = null
        var imageUri: android.net.Uri? = null

        try {
            val contentResolver = applicationContext.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FromIPhone")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            imageUri = contentResolver.insert(imageCollection, contentValues)

            if (imageUri != null) {
                writeStream = contentResolver.openOutputStream(imageUri)
                if (writeStream != null) {
                    // Kompresia 100 zachová úplný originál a kvalitu
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, writeStream)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(imageUri, contentValues, null, null)
                }
            }
            return imageUri
        } catch (e: Exception) {
            Log.e(tagLog, "Chyba pri zápise do galérie", e)
            return null
        } finally {
            writeStream?.close()
        }
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            statusText = "Inzercia nedostupná."
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        try {
            advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    super.onStartSuccess(settingsInEffect)
                    statusText = "Čakám na iPhone..."
                }

                override fun onStartFailure(errorCode: Int) {
                    super.onStartFailure(errorCode)
                    statusText = "Chyba inzercie: $errorCode"
                }
            })
        } catch (e: SecurityException) {
            statusText = "Chyba Security Advertise"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cancelReceiveTimeout()
            if (::healthSource.isInitialized) {
                healthSource.stopAll()
                flushHealth()
            }
            bluetoothGattServer?.close()
        } catch (e: SecurityException) {
            Log.e(tagLog, "Chyba onDestroy", e)
        }
    }

    companion object {
        /** How often buffered health samples are pushed to the phone. */
        private const val FLUSH_INTERVAL_MS = 5000L

        /** How far back a manual Samsung Health sync reaches. */

        /** A photo transfer with no progress for this long is abandoned. */
        private const val RECEIVE_TIMEOUT_MS = 15_000L
        private const val SYNC_WINDOW_HOURS = 24L
    }
}

@Composable
fun WatchScreen(
    statusText: String,
    bitmap: Bitmap?,
    healthText: String,
    isSendingPhoto: Boolean = false,
    photoProgress: Float = 0f,
    onRequestSamsungHealth: () -> Unit,
    onSyncHealth: () -> Unit
) {
    // ScreenScaffold keeps the clock in the curved bezel and gives the content a
    // correct shape for round watches, which a bare Column does not.
    ScreenScaffold(
        timeText = { TimeText() },
    ) { innerPadding ->
        if (bitmap != null) {
            // A received photo is the whole point of the app at that moment, so it
            // fills the screen rather than competing with controls.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Prijatá fotka",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            return@ScreenScaffold
        }

        // TimeText already occupies the top of the screen; VIGNETTE is set in
        // setContent and handled here by the scaffold, so the list starts below it.
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            item {
                Text(
                    text = statusText,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (isSendingPhoto) {
                item {
                    ArcProgressIndicator(
                        Modifier.size(72.dp),
                        photoProgress
                    )
                }
                item {
                    Text(
                        text = "Prijíma sa fotka: ${(photoProgress * 100).toInt()} %",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (healthText.isNotEmpty()) {
                item {
                    Card(onClick = onSyncHealth, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Zdravie",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = healthText,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                ListHeader { Text("Samsung Health") }
            }

            // Samsung Health raises its own consent sheet, which only appears for a
            // direct foreground tap. Wrapping the icon+label keeps that tap obvious
            // rather than hiding it behind a long-press or gesture.
            item {
                Button(
                    onClick = onRequestSamsungHealth,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape
                ) {
                    Text("Povoliť Samsung Health")
                }
            }

            item {
                Text(
                    text = "Kroky idú priamo zo senzora. Tep a spánok zo Samsung Health.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}