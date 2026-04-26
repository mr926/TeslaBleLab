package com.teslablelab.domain.protocol

import android.util.Log
import com.teslablelab.toHexString
import com.teslablelab.data.repository.LogLevel
import com.teslablelab.domain.crypto.TeslaCrypto
import com.tesla.generated.keys.Role
import com.tesla.generated.signatures.AES_GCM_Personalized_Signature_Data
import com.tesla.generated.signatures.KeyIdentity
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.Session_Info_Status
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import com.tesla.generated.vcsec.InformationRequest
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.KeyFormFactor
import com.tesla.generated.vcsec.KeyMetadata
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.PermissionChange
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.SignedMessage
import com.tesla.generated.vcsec.SignedMessage_information_E
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.WhitelistOperation
import com.google.protobuf.ByteString
import java.security.MessageDigest
import java.security.SecureRandom

enum class PairingState {
    IDLE,
    SESSION_INFO_REQUESTED,
    SESSION_ESTABLISHED,
    WHITELIST_OPERATION_SENT,
    WAITING_FOR_NFC_TAP,
    PAIRED,
    ERROR
}

enum class SessionState {
    DISCONNECTED,
    CONNECTING,
    DISCOVERING_SERVICES,
    REQUESTING_MTU,
    ESTABLISHING_SESSION,
    AUTHENTICATED,
    ERROR
}

enum class VehicleDataCategory {
    CHARGE,
    CLIMATE,
    DRIVE,
    CLOSURES,
    TIRE_PRESSURE,
    MEDIA
}

class PairingManager(
    private val vcsecCrypto: TeslaCrypto,
    private val vin: String
) {
    private val TAG = "PairingManager"

    // Per-domain sessions and crypto instances
    private val sessions = mutableMapOf<Domain, DomainSession>()
    private val cryptos = mutableMapOf<Domain, TeslaCrypto>()

    // VCSEC state (kept for backward compatibility)
    var state: PairingState = PairingState.IDLE
        private set
    var sessionState: SessionState = SessionState.DISCONNECTED
        private set

    // Deprecated single-session fields — delegate to VCSEC session
    val sessionKey: ByteArray? get() = getDomainSession(Domain.DOMAIN_VEHICLE_SECURITY).sessionKey
    val sessionCounter: Int get() = getDomainSession(Domain.DOMAIN_VEHICLE_SECURITY).sessionCounter
    val sessionHandle: Int get() = getDomainSession(Domain.DOMAIN_VEHICLE_SECURITY).sessionHandle
    val sessionEpoch: ByteArray get() = getDomainSession(Domain.DOMAIN_VEHICLE_SECURITY).sessionEpoch
    val vehiclePublicKey: ByteArray get() = getDomainSession(Domain.DOMAIN_VEHICLE_SECURITY).vehiclePublicKey

    private var requestUuid = ByteArray(16)
    private var routingAddress = ByteArray(16)
    private val pendingSessionInfoDomains = mutableSetOf<Domain>()
    private val pendingSessionInfoByUuid = mutableMapOf<String, Domain>()
    private val pendingRequestHashesByUuid = mutableMapOf<String, ByteArray>()
    private val pendingRequestHashesByRoutingAddress = mutableMapOf<String, ByteArray>()
    private val pendingRequestDomainsByUuid = mutableMapOf<String, Domain>()
    private val pendingRequestDomainsByRoutingAddress = mutableMapOf<String, Domain>()
    private val secureRandom = SecureRandom()

    private var logCallback: ((String, LogLevel) -> Unit)? = null

    init {
        cryptos[Domain.DOMAIN_VEHICLE_SECURITY] = vcsecCrypto
    }

    fun setLogCallback(callback: (String, LogLevel) -> Unit) {
        logCallback = callback
    }

    private fun log(message: String, level: LogLevel = LogLevel.DEBUG) {
        Log.d(TAG, message)
        logCallback?.invoke(message, level)
    }

    private fun randomBytes(size: Int): ByteArray {
        return ByteArray(size).also { secureRandom.nextBytes(it) }
    }

    fun getDomainSession(domain: Domain): DomainSession {
        return sessions.getOrPut(domain) { DomainSession(domain) }
    }

    fun getDomainCrypto(domain: Domain): TeslaCrypto? = cryptos[domain]

    fun isDomainAuthenticated(domain: Domain): Boolean =
        getDomainSession(domain).isAuthenticated

    fun hasDomainSessionKey(domain: Domain): Boolean =
        getDomainSession(domain).hasSessionKey()

    fun getPendingSessionInfoDomain(requestUuid: ByteArray? = null): Domain? {
        if (requestUuid != null && requestUuid.isNotEmpty()) {
            pendingSessionInfoByUuid[requestUuid.toHexString()]?.let { return it }
        }
        return pendingSessionInfoDomains.firstOrNull()
    }

    fun isPendingSessionInfo(domain: Domain): Boolean {
        return pendingSessionInfoDomains.contains(domain)
    }

    // ==================== Session Info Request ====================

    fun createSessionInfoRequest(domain: Domain = Domain.DOMAIN_VEHICLE_SECURITY): RoutableMessage {
        val crypto = cryptos[domain] ?: throw IllegalStateException("No crypto for domain $domain")
        log("Creating SessionInfoRequest for domain=$domain", LogLevel.INFO)

        val publicKeyBytes = crypto.getPublicKeyPoint()
        val uuid = randomBytes(16)
        val address = randomBytes(16)

        val sessionInfoRequest = SessionInfoRequest.newBuilder()
            .setPublicKey(ByteString.copyFrom(publicKeyBytes))
            .build()

        val message = RoutableMessage.newBuilder()
            .setToDestination(
                Destination.newBuilder()
                    .setDomain(domain)
                    .build()
            )
            .setFromDestination(
                Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(address))
                    .build()
            )
            .setSessionInfoRequest(sessionInfoRequest)
            .setUuid(ByteString.copyFrom(uuid))
            .build()

        pendingSessionInfoDomains.add(domain)
        pendingSessionInfoByUuid[uuid.toHexString()] = domain
        if (domain == Domain.DOMAIN_VEHICLE_SECURITY && state !in listOf(
                PairingState.WHITELIST_OPERATION_SENT,
                PairingState.WAITING_FOR_NFC_TAP,
                PairingState.PAIRED,
                PairingState.ERROR
            )
        ) {
            state = PairingState.SESSION_INFO_REQUESTED
        }
        log("SessionInfoRequest created for domain=$domain, pubkey size: ${publicKeyBytes.size}", LogLevel.INFO)
        return message
    }

    // ==================== Session Info Handling ====================

    enum class SessionInfoResult {
        SUCCESS,
        KEY_NOT_ON_WHITELIST,
        ERROR
    }

    fun handleSessionInfo(message: RoutableMessage): SessionInfoResult {
        val domain = if (pendingSessionInfoDomains.size == 1) {
            getPendingSessionInfoDomain(message.requestUuid.toByteArray()) ?: pendingSessionInfoDomains.first()
        } else if (pendingSessionInfoDomains.isNotEmpty()) {
            getPendingSessionInfoDomain(message.requestUuid.toByteArray())
                ?: pendingSessionInfoDomains.first { getDomainSession(it).sessionKey == null }
        } else {
            if (message.hasFromDestination() && message.fromDestination.hasDomain()) {
                message.fromDestination.domain
            } else {
                message.toDestination.domain
            }
        }
        pendingSessionInfoDomains.remove(domain)
        if (!message.requestUuid.isEmpty) {
            pendingSessionInfoByUuid.remove(message.requestUuid.toByteArray().toHexString())
        }
        log("handleSessionInfo: using domain=$domain (pending=${pendingSessionInfoDomains.size})", LogLevel.DEBUG)
        return handleSessionInfoForDomain(message, domain)
    }

    fun handleSessionInfoForDomain(message: RoutableMessage, domain: Domain): SessionInfoResult {
        val session = getDomainSession(domain)
        val crypto = cryptos[domain] ?: run {
            log("No crypto for domain=$domain", LogLevel.ERROR)
            return SessionInfoResult.ERROR
        }

        if (session.isAuthenticated && session.hasSessionKey()) {
            log("Session already established for domain=$domain, ignoring duplicate", LogLevel.DEBUG)
            return SessionInfoResult.SUCCESS
        }

        log("Handling SessionInfo response for domain=$domain", LogLevel.INFO)

        val sessionInfoByteString = message.sessionInfo
        val encodedSessionInfo: ByteArray
        if (sessionInfoByteString.isEmpty) {
            log("session_info is empty, checking protobuf_message_as_bytes", LogLevel.WARNING)
            val payloadBytes = message.protobufMessageAsBytes.toByteArray()
            if (payloadBytes.isNotEmpty()) {
                return try {
                    val sessionInfo = SessionInfo.parseFrom(payloadBytes)
                    log("Parsed SessionInfo: counter=${sessionInfo.counter}, status=${sessionInfo.status}", LogLevel.INFO)
                    processSessionInfo(sessionInfo, payloadBytes, message, domain, crypto, session)
                } catch (e: Exception) {
                    log("Failed to parse SessionInfo: ${e.message}", LogLevel.ERROR)
                    if (domain == Domain.DOMAIN_VEHICLE_SECURITY) state = PairingState.ERROR
                    SessionInfoResult.ERROR
                }
            }
            log("No session info data found", LogLevel.ERROR)
            if (domain == Domain.DOMAIN_VEHICLE_SECURITY) state = PairingState.ERROR
            return SessionInfoResult.ERROR
        }

        encodedSessionInfo = sessionInfoByteString.toByteArray()
        return try {
            val sessionInfo = SessionInfo.parseFrom(encodedSessionInfo)
            log("SessionInfo for domain=$domain: counter=${sessionInfo.counter}, status=${sessionInfo.status}, handle=${sessionInfo.handle}", LogLevel.INFO)
            processSessionInfo(sessionInfo, encodedSessionInfo, message, domain, crypto, session)
        } catch (e: Exception) {
            log("Failed to parse SessionInfo: ${e.message}", LogLevel.ERROR)
            if (domain == Domain.DOMAIN_VEHICLE_SECURITY) state = PairingState.ERROR
            SessionInfoResult.ERROR
        }
    }

    private fun processSessionInfo(
        sessionInfo: SessionInfo,
        encodedSessionInfo: ByteArray,
        message: RoutableMessage,
        domain: Domain,
        crypto: TeslaCrypto,
        session: DomainSession
    ): SessionInfoResult {
        log("Domain=$domain vehicle pubkey: ${sessionInfo.publicKey.toByteArray().toHexString()}", LogLevel.DEBUG)
        log("Domain=$domain epoch: ${sessionInfo.epoch.toByteArray().toHexString()}", LogLevel.DEBUG)
        log("Domain=$domain clock time: ${sessionInfo.clockTime}", LogLevel.DEBUG)

        if (sessionInfo.status == Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST) {
            log("Key not on whitelist for domain=$domain", LogLevel.WARNING)
            return SessionInfoResult.KEY_NOT_ON_WHITELIST
        }

        if (sessionInfo.publicKey.isEmpty) {
            log("No vehicle public key in SessionInfo for domain=$domain", LogLevel.ERROR)
            if (domain == Domain.DOMAIN_VEHICLE_SECURITY) state = PairingState.ERROR
            return SessionInfoResult.ERROR
        }

        session.vehiclePublicKey = sessionInfo.publicKey.toByteArray()
        session.sessionCounter = sessionInfo.counter.toInt()
        session.sessionEpoch = sessionInfo.epoch.toByteArray()
        session.sessionHandle = sessionInfo.handle.toInt()
        session.updateClock(sessionInfo.clockTime.toLong())

        return try {
            session.sessionKey = crypto.performECDH(session.vehiclePublicKey)
            val key = session.sessionKey
            if (key == null) {
                log("ECDH failed for domain=$domain: session key is null", LogLevel.ERROR)
                if (domain == Domain.DOMAIN_VEHICLE_SECURITY) state = PairingState.ERROR
                return SessionInfoResult.ERROR
            }
            log("Session key derived for domain=$domain: ${key.toHexString()}", LogLevel.INFO)

            if (!verifySessionInfoTag(crypto, key, encodedSessionInfo, message)) {
                log("SessionInfo HMAC verification failed for domain=$domain", LogLevel.ERROR)
                if (domain == Domain.DOMAIN_VEHICLE_SECURITY) state = PairingState.ERROR
                return SessionInfoResult.ERROR
            }

            session.isAuthenticated = true

            if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
                state = PairingState.SESSION_ESTABLISHED
                sessionState = SessionState.AUTHENTICATED
            }
            log("Session established for domain=$domain!", LogLevel.INFO)
            SessionInfoResult.SUCCESS
        } catch (e: Exception) {
            log("ECDH failed for domain=$domain: ${e.message}", LogLevel.ERROR)
            if (domain == Domain.DOMAIN_VEHICLE_SECURITY) state = PairingState.ERROR
            SessionInfoResult.ERROR
        }
    }

    private fun verifySessionInfoTag(
        crypto: TeslaCrypto,
        sessionKey: ByteArray,
        encodedSessionInfo: ByteArray,
        message: RoutableMessage
    ): Boolean {
        val challenge = message.requestUuid.toByteArray()
        val expectedTag = message.signatureData.sessionInfoTag.tag.toByteArray()
        if (challenge.isEmpty() || expectedTag.isEmpty()) {
            log("SessionInfo response missing challenge or HMAC tag", LogLevel.ERROR)
            return false
        }

        val subkey = crypto.deriveHmacSubkey(sessionKey, "session info")
        val hmacMessage = TeslaMetadata.serializeSessionInfoHmacMessage(
            verifierName = vin.toByteArray(),
            challenge = challenge,
            encodedSessionInfo = encodedSessionInfo
        )
        val actualTag = crypto.hmacSha256(subkey, hmacMessage)
        val ok = MessageDigest.isEqual(actualTag, expectedTag)
        if (!ok) {
            log("SessionInfo tag mismatch: expected=${expectedTag.toHexString()}, actual=${actualTag.toHexString()}", LogLevel.ERROR)
        }
        return ok
    }

    // ==================== VCSEC Pairing (unchanged) ====================

    fun createPairingRequest(): ByteArray {
        log("Creating pairing request (ToVCSECMessage)", LogLevel.INFO)
        val crypto = cryptos[Domain.DOMAIN_VEHICLE_SECURITY]!!

        val publicKeyBytes = crypto.getPublicKeyPoint()

        val permissionChange = PermissionChange.newBuilder()
            .setKey(
                PublicKey.newBuilder()
                    .setPublicKeyRaw(ByteString.copyFrom(publicKeyBytes))
                    .build()
            )
            .setKeyRole(Role.ROLE_DRIVER)
            .build()

        val whitelistOp = WhitelistOperation.newBuilder()
            .setAddKeyToWhitelistAndAddPermissions(permissionChange)
            .setMetadataForKey(
                KeyMetadata.newBuilder()
                    .setKeyFormFactor(KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE)
                    .build()
            )
            .build()

        val unsignedMessage = UnsignedMessage.newBuilder()
            .setWhitelistOperation(whitelistOp)
            .build()

        val signedMessage = SignedMessage.newBuilder()
            .setProtobufMessageAsBytes(ByteString.copyFrom(unsignedMessage.toByteArray()))
            .setSignatureType(com.tesla.generated.vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY)
            .build()

        val toVCSECMessage = ToVCSECMessage.newBuilder()
            .setSignedMessage(signedMessage)
            .build()

        state = PairingState.WHITELIST_OPERATION_SENT
        log("Pairing request created (DRIVER, ANDROID_DEVICE)", LogLevel.INFO)
        return toVCSECMessage.toByteArray()
    }

    fun markWaitingForNfcTap() {
        if (state != PairingState.PAIRED && state != PairingState.ERROR) {
            state = PairingState.WAITING_FOR_NFC_TAP
        }
    }

    fun createVehicleStatusRequest(): RoutableMessage {
        val infoRequest = InformationRequest.newBuilder()
            .setInformationRequestType(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS)
            .build()

        val unsignedMessage = UnsignedMessage.newBuilder()
            .setInformationRequest(infoRequest)
            .build()

        secureRandom.nextBytes(requestUuid)
        secureRandom.nextBytes(routingAddress)

        return RoutableMessage.newBuilder()
            .setToDestination(
                Destination.newBuilder()
                    .setDomain(Domain.DOMAIN_VEHICLE_SECURITY)
                    .build()
            )
            .setFromDestination(
                Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(routingAddress))
                    .build()
            )
            .setProtobufMessageAsBytes(ByteString.copyFrom(unsignedMessage.toByteArray()))
            .setUuid(ByteString.copyFrom(requestUuid))
            .setFlags(TeslaBleConstants.FLAG_ENCRYPT_RESPONSE_MASK)
            .build()
    }

    // ==================== CarServer Request ====================

    fun createGetVehicleDataRequest(category: VehicleDataCategory): RoutableMessage {
        val getVehicleData = com.tesla.generated.carserver.GetVehicleData.newBuilder().apply {
            when (category) {
                VehicleDataCategory.CHARGE ->
                    setGetChargeState(com.tesla.generated.carserver.GetChargeState.newBuilder().build())
                VehicleDataCategory.CLIMATE ->
                    setGetClimateState(com.tesla.generated.carserver.GetClimateState.newBuilder().build())
                VehicleDataCategory.DRIVE ->
                    setGetDriveState(com.tesla.generated.carserver.GetDriveState.newBuilder().build())
                VehicleDataCategory.CLOSURES ->
                    setGetClosuresState(com.tesla.generated.carserver.GetClosuresState.newBuilder().build())
                VehicleDataCategory.TIRE_PRESSURE ->
                    setGetTirePressureState(com.tesla.generated.carserver.GetTirePressureState.newBuilder().build())
                VehicleDataCategory.MEDIA ->
                    setGetMediaState(com.tesla.generated.carserver.GetMediaState.newBuilder().build())
            }
        }.build()

        val vehicleAction = com.tesla.generated.carserver.VehicleAction.newBuilder()
            .setGetVehicleData(getVehicleData)
            .build()

        val action = com.tesla.generated.carserver.Action.newBuilder()
            .setVehicleAction(vehicleAction)
            .build()

        val requestUuidBytes = ByteArray(16)
        val routingAddr = ByteArray(16)
        secureRandom.nextBytes(requestUuidBytes)
        secureRandom.nextBytes(routingAddr)

        return RoutableMessage.newBuilder()
            .setToDestination(
                Destination.newBuilder()
                    .setDomain(Domain.DOMAIN_INFOTAINMENT)
                    .build()
            )
            .setFromDestination(
                Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(routingAddr))
                    .build()
            )
            .setProtobufMessageAsBytes(ByteString.copyFrom(action.toByteArray()))
            .setUuid(ByteString.copyFrom(requestUuidBytes))
            .setFlags(TeslaBleConstants.FLAG_ENCRYPT_RESPONSE_MASK)
            .build()
    }

    // ==================== CarServer Response Parsing ====================

    fun handleCarServerResponse(payload: ByteArray): com.tesla.generated.carserver.VehicleData? {
        return try {
            val response = com.tesla.generated.carserver.Response.parseFrom(payload)
            Log.d(TAG, "CarServer Response: hasVehicleData=${response.hasVehicleData()}, hasActionStatus=${response.hasActionStatus()}")

            if (response.hasActionStatus()) {
                val status = response.actionStatus
                val reason = if (status.hasResultReason()) status.resultReason.plainText else ""
                Log.d(TAG, "ActionStatus: result=${status.result}, reason=$reason")
            }

            if (response.hasVehicleData()) {
                val vd = response.vehicleData
                log("VehicleData received:", LogLevel.INFO)
                if (vd.hasDriveState()) {
                    val ds = vd.driveState
                    log("  Drive: shift=${ds.shiftState}, speed=${ds.speedFloat}, power=${ds.power}, odo=${ds.odometerInHundredthsOfAMile}", LogLevel.INFO)
                }
                if (vd.hasChargeState()) {
                    val cs = vd.chargeState
                    log("  Charge: state=${cs.chargingState}, battery=${cs.batteryLevel}%, range=${cs.batteryRange}mi", LogLevel.INFO)
                }
                if (vd.hasClimateState()) {
                    val cl = vd.climateState
                    log("  Climate: in=${cl.insideTempCelsius}C, out=${cl.outsideTempCelsius}C, on=${cl.isClimateOn}", LogLevel.INFO)
                }
                if (vd.hasTirePressureState()) {
                    val tp = vd.tirePressureState
                    log("  Tires: FL=${tp.tpmsPressureFl}, FR=${tp.tpmsPressureFr}, RL=${tp.tpmsPressureRl}, RR=${tp.tpmsPressureRr}", LogLevel.INFO)
                }
                if (vd.hasMediaState()) {
                    val ms = vd.mediaState
                    log("  Media: ${ms.nowPlayingArtist} - ${ms.nowPlayingTitle}, vol=${ms.audioVolume}", LogLevel.INFO)
                }
                vd
            } else {
                Log.d(TAG, "CarServer Response has no VehicleData")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse CarServer Response", e)
            null
        }
    }

    // ==================== Encryption / Decryption ====================

    fun encryptMessage(message: RoutableMessage): ByteArray {
        val domain = message.toDestination.domain
        val session = getDomainSession(domain)
        val crypto = cryptos[domain] ?: throw IllegalStateException("No crypto for domain $domain")
        val key = session.sessionKey ?: throw IllegalStateException("No session key for domain $domain")

        session.sessionCounter++
        val counterForThisMessage = session.sessionCounter

        val protobufBytes = message.protobufMessageAsBytes.toByteArray()
        if (protobufBytes.isEmpty()) {
            throw IllegalStateException("No protobuf payload to encrypt")
        }
        log("Encrypting domain=$domain: payload=${protobufBytes.size}B, counter=$counterForThisMessage", LogLevel.INFO)

        val expiresAt = (session.currentClockTimeSeconds() + 5).toInt()

        val gcmData = AES_GCM_Personalized_Signature_Data.newBuilder()
            .setEpoch(ByteString.copyFrom(session.sessionEpoch))
            .setCounter(counterForThisMessage)
            .setExpiresAt(expiresAt)

        val sigDataBuilder = SignatureData.newBuilder()
            .setSignerIdentity(
                KeyIdentity.newBuilder()
                    .setPublicKey(ByteString.copyFrom(crypto.getPublicKeyPoint()))
                    .build()
            )
            .setAESGCMPersonalizedData(gcmData.build())

        val messageWithSig = RoutableMessage.newBuilder()
            .mergeFrom(message)
            .setSignatureData(sigDataBuilder.build())
            .build()

        val aad = TeslaMetadata.computePersonalizedAAD(
            messageWithSig, session.sessionEpoch, counterForThisMessage, expiresAt, vin.toByteArray()
        )

        val encryptedData = crypto.encryptAesGcm(protobufBytes, key, aad)

        val finalSigData = SignatureData.newBuilder()
            .setSignerIdentity(
                KeyIdentity.newBuilder()
                    .setPublicKey(ByteString.copyFrom(crypto.getPublicKeyPoint()))
                    .build()
            )
            .setAESGCMPersonalizedData(
                AES_GCM_Personalized_Signature_Data.newBuilder()
                    .setEpoch(ByteString.copyFrom(session.sessionEpoch))
                    .setNonce(ByteString.copyFrom(encryptedData.nonce))
                    .setCounter(counterForThisMessage)
                    .setExpiresAt(expiresAt)
                    .setTag(ByteString.copyFrom(encryptedData.tag))
                    .build()
            )
            .build()

        val requestUuidBytes = if (message.uuid.isEmpty) randomBytes(16) else message.uuid.toByteArray()

        val encryptedMessage = RoutableMessage.newBuilder()
            .mergeFrom(message)
            .setUuid(ByteString.copyFrom(requestUuidBytes))
            .setSignatureData(finalSigData)
            .setProtobufMessageAsBytes(ByteString.copyFrom(encryptedData.ciphertext))
            .clearSessionInfoRequest()
            .build()

        val requestHash = byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED_VALUE.toByte()) + encryptedData.tag
        pendingRequestHashesByUuid[requestUuidBytes.toHexString()] = requestHash
        pendingRequestDomainsByUuid[requestUuidBytes.toHexString()] = domain
        if (message.hasFromDestination() && !message.fromDestination.routingAddress.isEmpty) {
            val addressKey = message.fromDestination.routingAddress.toByteArray().toHexString()
            pendingRequestHashesByRoutingAddress[addressKey] = requestHash
            pendingRequestDomainsByRoutingAddress[addressKey] = domain
        }
        trimPendingRequests()

        log("Encrypted domain=$domain: counter=$counterForThisMessage, size=${encryptedMessage.serializedSize}", LogLevel.INFO)
        return TeslaBleMessage.encode(encryptedMessage)
    }

    fun decryptMessage(message: RoutableMessage): RoutableMessage? {
        // Detect domain from response
        val domain = inferResponseDomain(message) ?: Domain.DOMAIN_VEHICLE_SECURITY

        val session = getDomainSession(domain)
        val crypto = cryptos[domain] ?: run {
            Log.e(TAG, "No crypto for domain=$domain")
            return null
        }
        val key = session.sessionKey ?: run {
            Log.e(TAG, "No session key for domain=$domain")
            return null
        }

        val ciphertext = message.protobufMessageAsBytes.toByteArray()
        if (ciphertext.isEmpty()) {
            Log.d(TAG, "Message has no encrypted payload")
            return message
        }

        val sigData = message.signatureData
        val nonce: ByteArray
        val tag: ByteArray
        var aad: ByteArray? = null

        if (sigData.hasAESGCMPersonalizedData()) {
            val aesData = sigData.getAESGCMPersonalizedData()
            nonce = aesData.nonce.toByteArray()
            tag = aesData.tag.toByteArray()
            aad = TeslaMetadata.computePersonalizedAAD(
                message, aesData.epoch.toByteArray(), aesData.counter, aesData.expiresAt, vin.toByteArray()
            )
        } else if (sigData.hasAESGCMResponseData()) {
            val aesData = sigData.getAESGCMResponseData()
            nonce = aesData.nonce.toByteArray()
            tag = aesData.tag.toByteArray()
            val requestHash = findRequestHash(message) ?: run {
                Log.e(TAG, "Missing request hash for response request_uuid=${message.requestUuid.toByteArray().toHexString()}")
                return null
            }
            aad = TeslaMetadata.computeResponseAAD(message, aesData.counter, vin.toByteArray(), requestHash)
        } else {
            Log.w(TAG, "No AES-GCM signature data found")
            return message
        }

        Log.d(TAG, "Decrypting domain=$domain: nonce=${nonce.toHexString()}")

        val encryptedData = TeslaCrypto.EncryptedData(nonce, ciphertext, tag)
        val decrypted = try {
            crypto.decryptAesGcm(encryptedData, key, aad)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for domain=$domain", e)
            return null
        }

        Log.d(TAG, "Decrypted domain=$domain: ${decrypted.size} bytes")

        return RoutableMessage.newBuilder()
            .mergeFrom(message)
            .setProtobufMessageAsBytes(ByteString.copyFrom(decrypted))
            .clearSignatureData()
            .build()
    }

    fun inferResponseDomain(message: RoutableMessage): Domain? {
        if (message.hasFromDestination() && message.fromDestination.hasDomain()) {
            return message.fromDestination.domain
        }
        val requestUuid = message.requestUuid.toByteArray()
        if (requestUuid.isNotEmpty()) {
            pendingRequestDomainsByUuid[requestUuid.toHexString()]?.let { return it }
        }
        val uuid = message.uuid.toByteArray()
        if (uuid.isNotEmpty()) {
            pendingRequestDomainsByUuid[uuid.toHexString()]?.let { return it }
        }
        if (message.hasToDestination() && !message.toDestination.routingAddress.isEmpty) {
            pendingRequestDomainsByRoutingAddress[message.toDestination.routingAddress.toByteArray().toHexString()]?.let { return it }
        }
        return null
    }

    private fun findRequestHash(message: RoutableMessage): ByteArray? {
        val requestUuid = message.requestUuid.toByteArray()
        if (requestUuid.isNotEmpty()) {
            pendingRequestHashesByUuid[requestUuid.toHexString()]?.let { return it }
        }
        val uuid = message.uuid.toByteArray()
        if (uuid.isNotEmpty()) {
            pendingRequestHashesByUuid[uuid.toHexString()]?.let { return it }
        }
        if (message.hasToDestination() && !message.toDestination.routingAddress.isEmpty) {
            pendingRequestHashesByRoutingAddress[message.toDestination.routingAddress.toByteArray().toHexString()]?.let { return it }
        }
        return null
    }

    private fun trimPendingRequests(maxEntries: Int = 128) {
        trimMap(pendingRequestHashesByUuid, maxEntries)
        trimMap(pendingRequestHashesByRoutingAddress, maxEntries)
        trimMap(pendingRequestDomainsByUuid, maxEntries)
        trimMap(pendingRequestDomainsByRoutingAddress, maxEntries)
    }

    private fun <T> trimMap(map: MutableMap<String, T>, maxEntries: Int) {
        while (map.size > maxEntries) {
            map.remove(map.keys.first())
        }
    }

    // ==================== VCSEC Message Handling (unchanged) ====================

    fun handleFromVCSECMessage(payload: ByteArray): com.tesla.generated.vcsec.FromVCSECMessage? {
        return try {
            val fromVCSEC = com.tesla.generated.vcsec.FromVCSECMessage.parseFrom(payload)
            Log.d(TAG, "Parsed FromVCSECMessage")

            when {
                fromVCSEC.hasCommandStatus() -> {
                    val status = fromVCSEC.commandStatus
                    Log.d(TAG, "CommandStatus: operationStatus=${status.operationStatus}")

                    if (status.hasWhitelistOperationStatus()) {
                        val wlStatus = status.whitelistOperationStatus
                        when (wlStatus.operationStatus) {
                            OperationStatus_E.OPERATIONSTATUS_OK -> {
                                Log.d(TAG, "WhitelistOperation succeeded!")
                                state = PairingState.PAIRED
                            }
                            OperationStatus_E.OPERATIONSTATUS_WAIT -> {
                                Log.d(TAG, "WhitelistOperation waiting for NFC tap...")
                                state = PairingState.WAITING_FOR_NFC_TAP
                            }
                            OperationStatus_E.OPERATIONSTATUS_ERROR -> {
                                Log.e(TAG, "WhitelistOperation failed: ${wlStatus.whitelistOperationInformation}")
                                state = PairingState.ERROR
                            }
                            else -> {}
                        }
                    }

                    if (status.hasSignedMessageStatus()) {
                        val smStatus = status.signedMessageStatus
                        if (smStatus.signedMessageInformation != SignedMessage_information_E.SIGNEDMESSAGE_INFORMATION_NONE) {
                            Log.e(TAG, "SignedMessage error: ${smStatus.signedMessageInformation}")
                        }
                    }
                }
                fromVCSEC.hasVehicleStatus() -> {
                    val vs = fromVCSEC.vehicleStatus
                    log("VehicleStatus: lock=${vs.vehicleLockState}, sleep=${vs.vehicleSleepStatus}, presence=${vs.userPresence}", LogLevel.INFO)
                }
                fromVCSEC.hasWhitelistInfo() -> {
                    Log.d(TAG, "WhitelistInfo: entries=${fromVCSEC.whitelistInfo.numberOfEntries}")
                }
                fromVCSEC.hasNominalError() -> {
                    Log.e(TAG, "NominalError: ${fromVCSEC.nominalError.genericError}")
                }
            }

            fromVCSEC
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse FromVCSECMessage", e)
            null
        }
    }

    // ==================== Session Management ====================

    fun initInfotainmentCrypto() {
        cryptos[Domain.DOMAIN_INFOTAINMENT] = cryptos[Domain.DOMAIN_VEHICLE_SECURITY]!!
        log("Infotainment crypto initialized (reusing VCSEC key pair)", LogLevel.INFO)
    }

    fun reset() {
        Log.d(TAG, "Resetting pairing manager")
        state = PairingState.IDLE
        sessionState = SessionState.DISCONNECTED
        pendingSessionInfoDomains.clear()
        pendingSessionInfoByUuid.clear()
        pendingRequestHashesByUuid.clear()
        pendingRequestHashesByRoutingAddress.clear()
        pendingRequestDomainsByUuid.clear()
        pendingRequestDomainsByRoutingAddress.clear()
        sessions.values.forEach { it.reset() }
        // Keep VCSEC crypto, remove infotainment crypto
        cryptos.keys.filter { it != Domain.DOMAIN_VEHICLE_SECURITY }.forEach { cryptos.remove(it) }
    }

    fun resetDomain(domain: Domain) {
        Log.d(TAG, "Resetting session for domain=$domain")
        getDomainSession(domain).reset()
    }

    fun isPaired(): Boolean = state == PairingState.PAIRED
    fun isAuthenticated(): Boolean = sessionState == SessionState.AUTHENTICATED
    fun hasSessionKey(): Boolean = getDomainSession(Domain.DOMAIN_VEHICLE_SECURITY).hasSessionKey()
}
