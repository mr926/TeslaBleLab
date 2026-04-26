package com.teslablelab.domain.protocol

import com.jakewharton.timber.Timber
import com.teslablelab.data.proto.VehicleState as ProtoVehicleState
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class VehicleState(
    val speedFloat: Float = 0f,
    val power: Int = 0,
    val shiftState: String = "",
    val odometer: Float = 0f,
    val valBatteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val batteryVoltage: Float = 0f,
    val batteryCurrent: Float = 0f,
    val batteryPackTemperature: Float = 0f,
    val tpmsPressureFl: Int = 0,
    val tpmsPressureFr: Int = 0,
    val tpmsPressureRl: Int = 0,
    val tpmsPressureRr: Int = 0,
    val latitude: Float = 0f,
    val longitude: Float = 0f,
    val altitude: Float = 0f,
    val isParked: Boolean = false,
    val isDoorLocked: Boolean = false,
    val isFrunkOpen: Boolean = false,
    val isTrunkOpen: Boolean = false,
    val interiorTemperature: Float = 0f,
    val exteriorTemperature: Float = 0f,
    val hvacAutoRequest: Int = 0,
    val driverTempSetting: Float = 0f,
    val passengerTempSetting: Float = 0f
)

class VehicleStateDecoder {
    private val TAG = "VehicleStateDecoder"

    fun decode(data: ByteArray): VehicleState? {
        return try {
            Timber.tag(TAG).d("Decoding vehicle state, size: ${data.size} bytes")
            Timber.tag(TAG).d("Vehicle state data hex: ${data.toHexString()}")

            val protoState = ProtoVehicleState.parseFrom(data)
            Timber.tag(TAG).d("Parsed proto vehicle state")

            VehicleState(
                speedFloat = protoState.speedFloat,
                power = protoState.power,
                shiftState = protoState.shiftState,
                odometer = protoState.odometer,
                valBatteryLevel = protoState.batteryLevel,
                isCharging = protoState.isCharging,
                batteryVoltage = protoState.batteryVoltage,
                batteryCurrent = protoState.batteryCurrent,
                batteryPackTemperature = protoState.batteryPackTemperature,
                tpmsPressureFl = protoState.tpmsPressureFl,
                tpmsPressureFr = protoState.tpmsPressureFr,
                tpmsPressureRl = protoState.tpmsPressureRl,
                tpmsPressureRr = protoState.tpmsPressureRr,
                latitude = protoState.latitude,
                longitude = protoState.longitude,
                altitude = protoState.altitude,
                isParked = protoState.isParked,
                isDoorLocked = protoState.isDoorLocked,
                isFrunkOpen = protoState.isFrunkOpen,
                isTrunkOpen = protoState.isTrunkOpen,
                interiorTemperature = protoState.interiorTemperature,
                exteriorTemperature = protoState.exteriorTemperature,
                hvacAutoRequest = protoState.hvacAutoRequest,
                driverTempSetting = protoState.driverTempSetting,
                passengerTempSetting = protoState.passengerTempSetting
            ).also {
                Timber.tag(TAG).d("Decoded vehicle state: speed=${it.speedFloat}, power=${it.power}, shift=${it.shiftState}")
                Timber.tag(TAG).d("Odometer=${it.odometer}, battery=${it.valBatteryLevel}%")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to decode vehicle state")
            null
        }
    }

    fun decodeFromNativeFormat(data: ByteArray): VehicleState? {
        return try {
            Timber.tag(TAG).d("Decoding native format vehicle state, size: ${data.size} bytes")
            Timber.tag(TAG).d("Data hex: ${data.toHexString()}")

            if (data.size < 4) {
                Timber.tag(TAG).e("Data too short")
                return null
            }

            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.BIG_ENDIAN)

            var offset = 0
            while (offset < data.size) {
                if (offset + 4 > data.size) break

                val fieldId = buffer.get(offset).toInt() and 0xFF
                val wireType = buffer.get(offset + 1).toInt() and 0xFF
                offset += 2

                val value: Any = when (wireType) {
                    0 -> {
                        val v = buffer.get(offset).toInt() and 0xFF
                        offset += 1
                        v
                    }
                    1 -> {
                        if (offset + 8 > data.size) break
                        val v = buffer.long
                        offset += 8
                        v
                    }
                    5 -> {
                        if (offset + 4 > data.size) break
                        val v = buffer.int
                        offset += 4
                        v
                    }
                    else -> break
                }

                Timber.tag(TAG).d("Field $fieldId = $value")
            }

            VehicleState()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to decode native format")
            null
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
