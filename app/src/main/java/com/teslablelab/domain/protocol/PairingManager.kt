package com.teslablelab.domain.protocol

import com.jakewharton.timber.Timber
import com.teslablelab.domain.crypto.TeslaCrypto
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PairingState {
    IDLE,
    WAITING_FOR_CHALLENGE,
    WAITING_FOR_KEY_EXCHANGE_ACK,
    WAITING_FOR_SESSION_RESPONSE,
    PAIRED,
    ERROR
}

enum class SessionState {
    DISCONNECTED,
    CONNECTING,
    DISCOVERING_SERVICES,
    REQUESTING_MTU,
    PAIRING,
    AUTHENTICATED,
    SUBSCRIBED,
    ERROR
}

class PairingManager(
    private val crypto: TeslaCrypto,
    private val vin: String
) {
    private val TAG = "PairingManager"

    var state: PairingState = PairingState.IDLE
        private set

    var sessionState: SessionState = SessionState.DISCONNECTED
        private set

    var sessionId: Int = 0
        private set

    var sessionToken: Int = 0
        private set

    private var sharedSecret: ByteArray? = null
    private var outgoingKey: ByteArray? = null
    private var incomingKey: ByteArray? = null
    private var serverEphemeralPubkey: ByteArray? = null

    private val clientNonce: ByteArray = crypto.generateNonce()
    private var serverNonce: ByteArray? = null
    private var challenge: ByteArray? = null
    private var signature: ByteArray? = null

    fun startPairing() {
        Timber.tag(TAG).d("Starting pairing process for VIN: $vin")
        Timber.tag(TAG).d("Client nonce: ${clientNonce.toHexString()}")
        state = PairingState.WAITING_FOR_CHALLENGE
    }

    fun handleSignatureChallenge(data: ByteArray): ByteArray {
        Timber.tag(TAG).d("Handling signature challenge")
        Timber.tag(TAG).d("Challenge data: ${data.toHexString()}")

        if (data.size < 32) {
            Timber.tag(TAG).e("Invalid challenge data size")
            state = PairingState.ERROR
            throw IllegalArgumentException("Invalid challenge data")
        }

        challenge = data.copyOfRange(0, 32)
        serverNonce = if (data.size > 32) data.copyOfRange(32, data.size) else ByteArray(0)

        Timber.tag(TAG).d("Challenge: ${challenge?.toHexString()}")
        Timber.tag(TAG).d("Server nonce: ${serverNonce?.toHexString()}")

        val dataToSign = challenge!! + vin.toByteArray()
        signature = crypto.sign(dataToSign)
        Timber.tag(TAG).d("Signature: ${signature?.toHexString()}")

        state = PairingState.WAITING_FOR_KEY_EXCHANGE_ACK
        return signature!!
    }

    fun handleKeyExchangeAck(data: ByteArray): Boolean {
        Timber.tag(TAG).d("Handling key exchange ack")

        if (data.size < 65) {
            Timber.tag(TAG).e("Invalid key exchange ack data size")
            state = PairingState.ERROR
            return false
        }

        serverEphemeralPubkey = data.copyOfRange(0, 65)
        val signatureFromServer = if (data.size > 65) {
            data.copyOfRange(65, data.size)
        } else {
            ByteArray(0)
        }

        Timber.tag(TAG).d("Server ephemeral pubkey: ${serverEphemeralPubkey?.toHexString()}")
        Timber.tag(TAG).d("Server signature: ${signatureFromServer.toHexString()}")

        val challengeData = serverEphemeralPubkey!! + clientNonce
        val isValid = crypto.verify(serverEphemeralPubkey!!, challengeData, signatureFromServer)

        if (!isValid) {
            Timber.tag(TAG).e("Signature verification failed!")
            state = PairingState.ERROR
            return false
        }

        sharedSecret = crypto.performEcdh(serverEphemeralPubkey!!)
        incomingKey = crypto.deriveSessionKey(sharedSecret!!, serverNonce!!, isOutgoing = false)
        outgoingKey = crypto.deriveSessionKey(sharedSecret!!, clientNonce, isOutgoing = true)

        Timber.tag(TAG).d("Session keys derived successfully")
        Timber.tag(TAG).d("Outgoing key: ${outgoingKey?.toHexString()}")
        Timber.tag(TAG).d("Incoming key: ${incomingKey?.toHexString()}")

        state = PairingState.WAITING_FOR_SESSION_RESPONSE
        return true
    }

    fun handleSessionResponse(data: ByteArray): Boolean {
        Timber.tag(TAG).d("Handling session response")

        if (data.size < 12) {
            Timber.tag(TAG).e("Invalid session response size")
            sessionState = SessionState.ERROR
            return false
        }

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.BIG_ENDIAN)

        sessionId = buffer.int
        sessionToken = buffer.int
        val status = buffer.int

        Timber.tag(TAG).d("Session ID: $sessionId")
        Timber.tag(TAG).d("Session Token: $sessionToken")
        Timber.tag(TAG).d("Status: $status")

        if (status != TeslaBleConstants.STATUS_SUCCESS) {
            Timber.tag(TAG).e("Session establishment failed with status: $status")
            sessionState = SessionState.ERROR
            state = PairingState.ERROR
            return false
        }

        sessionState = SessionState.AUTHENTICATED
        state = PairingState.PAIRED
        Timber.tag(TAG).d("Session established successfully!")
        return true
    }

    fun createSessionRequestMessage(): TeslaBleMessage {
        Timber.tag(TAG).d("Creating session request message")

        val payload = ByteBuffer.allocate(13)
        payload.order(ByteOrder.BIG_ENDIAN)
        payload.putInt(TeslaBleConstants.API_VERSION)
        payload.putInt(TeslaBleConstants.SESSION_TYPE_NORMAL)
        payload.put(clientNonce)

        val sessionRequest = TeslaBleMessage.createMessage(
            TeslaBleConstants.MESSAGE_TYPE_SESSION_REQUEST,
            sessionId = 0,
            payload = payload.array()
        )

        return sessionRequest
    }

    fun createKeyExchangeMessage(): TeslaBleMessage {
        Timber.tag(TAG).d("Creating key exchange message")

        val publicKeyPoint = crypto.getPublicKeyPoint()
        val payload = ByteBuffer.allocate(64 + 64 + publicKeyPoint.size)
        payload.order(ByteOrder.BIG_ENDIAN)

        payload.put(clientNonce)
        payload.put(ByteArray(32))
        payload.put(publicKeyPoint)

        return TeslaBleMessage.createMessage(
            TeslaBleConstants.MESSAGE_TYPE_KEY_EXCHANGE,
            sessionId = 0,
            payload = payload.array()
        )
    }

    fun createSignatureResponseMessage(): TeslaBleMessage {
        Timber.tag(TAG).d("Creating signature response message")

        return TeslaBleMessage.createMessage(
            TeslaBleConstants.MESSAGE_TYPE_SIGNATURE_RESPONSE,
            sessionId = 0,
            payload = signature ?: ByteArray(0)
        )
    }

    fun createSubscribeMessage(): TeslaBleMessage {
        Timber.tag(TAG).d("Creating subscribe message")

        val subscriptionIds = listOf(
            TeslaBleConstants.SUBSCRIPTION_ID_VEHICLE_STATE
        ).toByteArray()

        val payload = ByteBuffer.allocate(4 + subscriptionIds.size * 4)
        payload.order(ByteOrder.BIG_ENDIAN)
        payload.putInt(subscriptionIds.size)
        subscriptionIds.forEach { payload.putInt(it.toInt()) }

        return TeslaBleMessage.createMessage(
            TeslaBleConstants.MESSAGE_TYPE_SUBSCRIBE,
            sessionId = sessionId,
            payload = payload.array()
        )
    }

    fun encryptOutgoingMessage(message: TeslaBleMessage): ByteArray {
        val nonce = crypto.generateNonce()
        val plaintext = message.toBytes()

        val encryptedData = crypto.encryptAesGcm(plaintext, outgoingKey!!, nonce)

        val payload = ByteBuffer.allocate(12 + encryptedData.ciphertext.size + 16)
        payload.order(ByteOrder.BIG_ENDIAN)
        payload.put(nonce)
        payload.put(encryptedData.ciphertext)
        payload.put(encryptedData.authTag)

        val encryptedMessage = TeslaBleMessage.createMessage(
            TeslaBleConstants.MESSAGE_TYPE_CRYPTO,
            sessionId = message.sessionId,
            payload = payload.array()
        )

        return encryptedMessage.toBytes()
    }

    fun decryptIncomingMessage(message: TeslaBleMessage): TeslaBleMessage? {
        if (message.payload.size < 12 + 16) {
            Timber.tag(TAG).e("Invalid encrypted message payload size")
            return null
        }

        val nonce = message.payload.copyOfRange(0, 12)
        val ciphertext = message.payload.copyOfRange(12, message.payload.size - 16)
        val authTag = message.payload.copyOfRange(message.payload.size - 16, message.payload.size)

        val encryptedData = TeslaCrypto.EncryptedData(ciphertext, authTag)
        val decrypted = crypto.decryptAesGcm(encryptedData, incomingKey!!, nonce)

        return TeslaBleMessage.parse(decrypted)
    }

    fun createTerminateMessage(): TeslaBleMessage {
        return TeslaBleMessage.createMessage(
            TeslaBleConstants.MESSAGE_TYPE_SESSION_TERMINATE,
            sessionId = sessionId,
            payload = ByteArray(0)
        )
    }

    fun reset() {
        Timber.tag(TAG).d("Resetting pairing manager")
        state = PairingState.IDLE
        sessionState = SessionState.DISCONNECTED
        sessionId = 0
        sessionToken = 0
        sharedSecret = null
        outgoingKey = null
        incomingKey = null
        serverEphemeralPubkey = null
        serverNonce = null
        challenge = null
        signature = null
    }

    fun getSharedSecret(): ByteArray? = sharedSecret

    fun isPaired(): Boolean = state == PairingState.PAIRED

    fun isAuthenticated(): Boolean = sessionState == SessionState.AUTHENTICATED || sessionState == SessionState.SUBSCRIBED

    private fun List<Int>.toByteArray(): ByteArray {
        return ByteArray(size) { index -> this[index].toByte() }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
