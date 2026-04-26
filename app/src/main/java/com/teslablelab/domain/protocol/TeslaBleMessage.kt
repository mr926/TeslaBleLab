package com.teslablelab.domain.protocol

import com.jakewharton.timber.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TeslaBleMessage {
    private companion object {
        private const val TAG = "TeslaBleMessage"
        private const val HEADER_SIZE = 6
    }

    var messageType: Int = 0
    var sessionId: Int = 0
    var payload: ByteArray = ByteArray(0)

    fun toBytes(): ByteArray {
        Timber.tag(TAG).d("Encoding message: type=${TeslaBleConstants.getMessageTypeName(messageType)}, sessionId=$sessionId, payloadSize=${payload.size}")
        Timber.tag(TAG).d("Payload hex: ${payload.toHexString()}")

        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(HEADER_SIZE.toShort())
        buffer.putInt(sessionId)
        buffer.put(messageType.toByte())
        buffer.put(payload)

        return buffer.array()
    }

    companion object {
        fun parse(data: ByteArray): TeslaBleMessage? {
            return try {
                if (data.size < HEADER_SIZE) {
                    Timber.tag(TAG).e("Message too short: ${data.size} bytes")
                    return null
                }

                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.BIG_ENDIAN)

                val length = buffer.short.toInt() and 0xFFFF
                val sessionId = buffer.int
                val messageType = buffer.get().toInt() and 0xFF
                val payload = if (data.size > HEADER_SIZE) {
                    data.copyOfRange(HEADER_SIZE, data.size)
                } else {
                    ByteArray(0)
                }

                Timber.tag(TAG).d("Parsed message: type=${TeslaBleConstants.getMessageTypeName(messageType)}, sessionId=$sessionId, payloadSize=${payload.size}")
                Timber.tag(TAG).d("Payload hex: ${payload.toHexString()}")

                TeslaBleMessage().apply {
                    this.sessionId = sessionId
                    this.messageType = messageType
                    this.payload = payload
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to parse message")
                null
            }
        }

        fun createMessage(type: Int, sessionId: Int = 0, payload: ByteArray = ByteArray(0)): TeslaBleMessage {
            return TeslaBleMessage().apply {
                messageType = type
                this.sessionId = sessionId
                this.payload = payload
            }
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
