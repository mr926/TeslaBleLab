package com.teslablelab.domain.protocol

import android.util.Log
import java.security.MessageDigest

object TeslaBleConstants {
    private const val TAG = "TeslaBleConstants"

    const val VEHICLE_SERVICE_UUID = "00000211-b2d1-43f0-9b88-960cebf8b91e"
    const val TO_VEHICLE_UUID = "00000212-b2d1-43f0-9b88-960cebf8b91e"
    const val FROM_VEHICLE_UUID = "00000213-b2d1-43f0-9b88-960cebf8b91e"

    const val MTU_SIZE = 512
    const val MAX_BLE_MESSAGE_SIZE = 1024
    const val SHARED_KEY_SIZE_BYTES = 16
    const val FLAG_ENCRYPT_RESPONSE_MASK = 1 shl 1

    const val DEFAULT_VIN = "LRWYGCEJ1SC437215"

    fun vehicleLocalName(vin: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val hash = digest.digest(vin.toByteArray())
        val hex = hash.take(8).joinToString("") { "%02x".format(it) }
        val name = "S${hex}C"
        Log.d(TAG, "VIN: $vin -> LocalName: $name")
        return name
    }

    fun logBleUuid(serviceUuid: String?, characteristicUuid: String?) {
        Log.d(TAG, "Service UUID: $serviceUuid")
        Log.d(TAG, "Characteristic UUID: $characteristicUuid")
    }
}
