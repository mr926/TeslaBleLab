package com.teslablelab.domain.protocol

import android.util.Log
import com.teslablelab.toHexString
import com.tesla.generated.universalmessage.RoutableMessage
import com.google.protobuf.ByteString

object TeslaBleMessage {
    private const val TAG = "TeslaBleMessage"
    private const val HEADER_SIZE = 2

    fun encode(message: RoutableMessage): ByteArray {
        val protobufBytes = message.toByteArray()
        Log.d(TAG, "Encoding RoutableMessage, protobuf size: ${protobufBytes.size}")
        Log.d(TAG, "Protobuf hex: ${protobufBytes.toHexString()}")

        val out = ByteArray(HEADER_SIZE + protobufBytes.size)
        out[0] = (protobufBytes.size shr 8).toByte()
        out[1] = protobufBytes.size.toByte()
        System.arraycopy(protobufBytes, 0, out, HEADER_SIZE, protobufBytes.size)

        return out
    }

    fun encodeRaw(payload: ByteArray): ByteArray {
        Log.d(TAG, "Encoding raw payload, size: ${payload.size}")
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = (payload.size shr 8).toByte()
        out[1] = payload.size.toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    fun parse(data: ByteArray): RoutableMessage? {
        return try {
            if (data.size < HEADER_SIZE) {
                Log.e(TAG, "Message too short: ${data.size} bytes")
                return null
            }

            val msgLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            if (data.size < HEADER_SIZE + msgLength) {
                Log.e(TAG, "Incomplete message: expected ${HEADER_SIZE + msgLength}, got ${data.size}")
                return null
            }

            val protobufBytes = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + msgLength)
            Log.d(TAG, "Parsing RoutableMessage, protobuf size: $msgLength")
            Log.d(TAG, "Protobuf hex: ${protobufBytes.toHexString()}")

            val message = RoutableMessage.parseFrom(protobufBytes)
            Log.d(TAG, "Parsed RoutableMessage successfully")
            message
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RoutableMessage", e)
            null
        }
    }

    fun parsePayload(data: ByteArray): RoutableMessage? {
        return try {
            Log.d(TAG, "Parsing raw protobuf payload, size: ${data.size}")
            Log.d(TAG, "Payload hex: ${data.toHexString()}")
            val message = RoutableMessage.parseFrom(data)
            Log.d(TAG, "Parsed RoutableMessage from raw payload")
            message
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse raw payload", e)
            null
        }
    }
}
