package com.teslablelab.domain.protocol

import android.util.Log
import com.teslablelab.toHexString
import com.tesla.generated.signatures.Tag
import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.universalmessage.RoutableMessage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object TeslaMetadata {
    private const val TAG_NAME = "TeslaMetadata"

    private const val TAG_END: Byte = Tag.TAG_END_VALUE.toByte()

    fun computePersonalizedAAD(
        message: RoutableMessage,
        epoch: ByteArray,
        counter: Int,
        expiresAt: Int,
        verifierName: ByteArray
    ): ByteArray {
        val buffer = ByteArrayOutputStream()

        addField(buffer, Tag.TAG_SIGNATURE_TYPE_VALUE, byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED_VALUE.toByte()))

        val domain = message.toDestination.domain
        if (domain != null && domain.number > 0) {
            addField(buffer, Tag.TAG_DOMAIN_VALUE, byteArrayOf(domain.number.toByte()))
        }

        addField(buffer, Tag.TAG_PERSONALIZATION_VALUE, verifierName)

        addField(buffer, Tag.TAG_EPOCH_VALUE, epoch)

        addField(buffer, Tag.TAG_EXPIRES_AT_VALUE, intToBigEndianBytes(expiresAt))

        addField(buffer, Tag.TAG_COUNTER_VALUE, intToBigEndianBytes(counter))

        if (message.flags > 0) {
            addField(buffer, Tag.TAG_FLAGS_VALUE, intToBigEndianBytes(message.flags))
        }

        buffer.write(TAG_END.toInt())

        val metadataBytes = buffer.toByteArray()
        Log.d(TAG_NAME, "Metadata for AAD: ${metadataBytes.toHexString()}")

        val digest = MessageDigest.getInstance("SHA-256")
        val aad = digest.digest(metadataBytes)
        Log.d(TAG_NAME, "AAD (SHA-256): ${aad.toHexString()}")
        return aad
    }

    fun computeResponseAAD(
        message: RoutableMessage,
        counter: Int,
        verifierName: ByteArray,
        requestHash: ByteArray
    ): ByteArray {
        val buffer = ByteArrayOutputStream()

        addField(buffer, Tag.TAG_SIGNATURE_TYPE_VALUE, byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE_VALUE.toByte()))

        val fromDomain = message.fromDestination?.domain
        if (fromDomain != null && fromDomain.number > 0) {
            addField(buffer, Tag.TAG_DOMAIN_VALUE, byteArrayOf(fromDomain.number.toByte()))
        }

        addField(buffer, Tag.TAG_PERSONALIZATION_VALUE, verifierName)

        addField(buffer, Tag.TAG_COUNTER_VALUE, intToBigEndianBytes(counter))

        addField(buffer, Tag.TAG_FLAGS_VALUE, intToBigEndianBytes(message.flags))

        addField(buffer, Tag.TAG_REQUEST_HASH_VALUE, requestHash)

        val fault = message.signedMessageStatus.signedMessageFault
        addField(buffer, Tag.TAG_FAULT_VALUE, intToBigEndianBytes(fault.number))

        buffer.write(TAG_END.toInt())

        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(buffer.toByteArray())
    }

    fun serializeSessionInfoHmacMessage(
        verifierName: ByteArray,
        challenge: ByteArray,
        encodedSessionInfo: ByteArray
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        addField(buffer, Tag.TAG_SIGNATURE_TYPE_VALUE, byteArrayOf(SignatureType.SIGNATURE_TYPE_HMAC_VALUE.toByte()))
        addField(buffer, Tag.TAG_PERSONALIZATION_VALUE, verifierName)
        addField(buffer, Tag.TAG_CHALLENGE_VALUE, challenge)
        buffer.write(TAG_END.toInt())
        buffer.write(encodedSessionInfo, 0, encodedSessionInfo.size)
        return buffer.toByteArray()
    }

    private fun addField(buffer: ByteArrayOutputStream, tag: Int, value: ByteArray) {
        buffer.write(tag)
        buffer.write(value.size)
        buffer.write(value, 0, value.size)
    }

    private fun intToBigEndianBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun intToBigEndianBytes(value: Long): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }
}
