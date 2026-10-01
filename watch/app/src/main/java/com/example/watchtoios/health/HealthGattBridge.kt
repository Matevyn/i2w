package com.example.watchbridge.health

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.util.Log
import java.util.UUID

/**
 * Pushes health payloads from the watch to the phone.
 *
 * The watch is already the GATT *server* (it advertises and the iPhone connects
 * as central), so phone-bound data goes out over a NOTIFY characteristic that
 * the iPhone subscribes to. That is the mirror image of the photo flow, where
 * the iPhone writes into a WRITE characteristic.
 *
 * Registering the characteristic is done by [attach]; notifications only start
 * once the iPhone has enabled the CCCD subscription, which is why
 * [onSubscriptionChanged] gates [notify].
 */
class HealthGattBridge(
    private val server: BluetoothGattServer,
    private val serviceUuid: UUID,
    private val characteristicUuid: UUID
) {

    private val characteristic = BluetoothGattCharacteristic(
        characteristicUuid,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    )

    /**
     * Client Characteristic Configuration Descriptor. A NOTIFY characteristic
     * is inert without one: the phone cannot enable notifications, and
     * notifyCharacteristicChanged will not reach it.
     */
    private val cccd = BluetoothGattDescriptor(
        UUID_CLIENT_CHARACTERISTIC_CONFIG,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
    )

    private var subscribedDevice: BluetoothDevice? = null

    /**
     * Largest notify payload we will emit. BluetoothGattServer exposes no MTU
     * getter, so this is set from the connection callback's negotiated value and
     * starts at the 20-byte ATT default until then.
     */
    var maxPayloadBytes: Int = DEFAULT_PAYLOAD
        set(value) { field = value.coerceIn(20, 512) }

    /** Adds the notify characteristic and its CCCD to the watch's service. */
    fun attach(service: android.bluetooth.BluetoothGattService) {
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)
    }

    /** Called when the phone enables or disables notifications on this characteristic. */
    fun onSubscriptionChanged(device: BluetoothDevice?, enabled: Boolean) {
        subscribedDevice = if (enabled) device else null
        Log.i(TAG, "Health notifications ${if (enabled) "enabled" else "disabled"} by ${device?.address}")
    }

    /**
     * Sends one batch, split into MTU-sized notifications. Does nothing when no
     * phone is subscribed, which is the normal case while the two apps are not
     * both running.
     */
    fun send(samples: List<HealthSample>, sleep: List<SleepInterval>): Boolean {
        val device = subscribedDevice ?: return false
        if (samples.isEmpty() && sleep.isEmpty()) return false

        return try {
            val chunks = HealthPayload.chunk(samples, sleep, maxPayloadBytes)
            if (chunks.isEmpty()) {
                Log.w(TAG, "Batch of ${samples.size} samples does not fit ${maxPayloadBytes}B")
                return false
            }

            for (chunk in chunks) {
                if (chunk.size > maxPayloadBytes - 3) {
                    Log.w(TAG, "Chunk ${chunk.size}B exceeds MTU payload")
                    return false
                }
                characteristic.value = chunk
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
            Log.i(TAG, "Sent ${chunks.size} chunk(s) for ${samples.size} samples")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Health notify security error", e)
            false
        }
    }

    companion object {
        private const val TAG = "HealthGattBridge"

        /**
         * The standard CCCD. There is no platform constant for it, so it is
         * spelled out: 0x2902 in the Bluetooth SIG base UUID.
         */
        val UUID_CLIENT_CHARACTERISTIC_CONFIG: java.util.UUID =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 20-byte ATT default payload before the MTU is negotiated. */
        private const val DEFAULT_PAYLOAD = 20

        /**
         * Health data flows over a separate characteristic so it can never be
         * confused with the photo protocol on the write characteristic.
         */
        val HEALTH_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("0f0e0d0c-0b0a-0908-0706-050405030201")
    }
}