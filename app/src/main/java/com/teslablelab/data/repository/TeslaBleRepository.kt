package com.teslablelab.data.repository

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.teslablelab.data.storage.SecureStorage
import com.teslablelab.domain.crypto.TeslaCrypto
import com.teslablelab.domain.model.TeslaVehicleSnapshot
import com.teslablelab.domain.protocol.*
import com.teslablelab.toHexString
import com.tesla.generated.universalmessage.*
import com.tesla.generated.vcsec.*
import com.tesla.generated.carserver.DriveState
import com.tesla.generated.carserver.ChargeState
import com.tesla.generated.carserver.ClimateState
import com.tesla.generated.carserver.ClosuresState as CarServerClosuresState
import com.tesla.generated.carserver.TirePressureState
import com.tesla.generated.carserver.MediaState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import java.util.*

data class BleConnectionState(
    val state: SessionState = SessionState.DISCONNECTED,
    val vin: String = "",
    val deviceAddress: String = "",
    val deviceName: String = "",
    val errorMessage: String? = null,
    val lastUpdateTime: Long = 0,
    val vehicleStatus: VehicleStatus? = null,
    val pairingState: PairingState = PairingState.IDLE,
    // Infotainment (CarServer) data
    val infotainmentSessionActive: Boolean = false,
    val infotainmentError: String? = null,
    val driveState: DriveState? = null,
    val chargeState: ChargeState? = null,
    val climateState: ClimateState? = null,
    val carServerClosuresState: CarServerClosuresState? = null,
    val tirePressureState: TirePressureState? = null,
    val mediaState: MediaState? = null,
    val snapshot: TeslaVehicleSnapshot = TeslaVehicleSnapshot.EMPTY
)

data class BleLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.DEBUG,
    val message: String
)

enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR
}

class TeslaBleRepository(
    private val context: Context
) {
    private val TAG = "TeslaBleRepository"
    private val schedulerTickMs = 50L
    private val vcsecPollingIntervalMs = 1000L
    private val connectTimeoutMs = 30_000L
    private val maxPollingWriteQueueSize = 4

    private data class TeslaScanMatch(
        val device: BluetoothDevice,
        val localName: String,
        val address: String,
        val rssi: Int,
        val connectable: Boolean,
        val seenAtMs: Long = System.currentTimeMillis()
    )

    private data class CarServerPollSpec(
        val category: VehicleDataCategory,
        val intervalMs: Long
    )

    private val carServerPollSpecs = listOf(
        CarServerPollSpec(VehicleDataCategory.DRIVE, 250L),
        CarServerPollSpec(VehicleDataCategory.CLOSURES, 1000L),
        CarServerPollSpec(VehicleDataCategory.MEDIA, 2000L),
        CarServerPollSpec(VehicleDataCategory.CHARGE, 6000L),
        CarServerPollSpec(VehicleDataCategory.CLIMATE, 9000L),
        CarServerPollSpec(VehicleDataCategory.TIRE_PRESSURE, 15000L)
    )

    private val secureStorage = SecureStorage(context)
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val crypto = TeslaCrypto()
    private var pairingManager: PairingManager? = null
    private var activeKeyVin: String? = null
    private var activeKeyPersisted = false

    fun hasSavedKey(): Boolean = secureStorage.hasExistingPairing(vin)

    fun deleteSavedKey() {
        log("Deleting local pairing key for VIN: $vin", LogLevel.INFO)
        disconnect()
        val cleared = secureStorage.clearAllPairingData()
        crypto.generateKeyPair()
        activeKeyVin = vin
        activeKeyPersisted = false
        pairingManager = null
        log(
            "Local pairing key deleted (storageCleared=$cleared); generated a fresh in-memory key fp=${currentKeyFingerprint()}",
            LogLevel.INFO
        )
    }

    private fun prepareKeyForVin(vin: String) {
        val savedVin = secureStorage.getSavedVin()
        val privateKeyBytes = secureStorage.getEncryptedPrivateKey()
        val savedPublicKey = secureStorage.getPublicKey()

        if (savedVin == vin && privateKeyBytes != null) {
            val loaded = crypto.loadPrivateKey(privateKeyBytes)
            if (loaded) {
                activeKeyVin = vin
                activeKeyPersisted = true
                val activeFp = currentKeyFingerprint()
                val savedFp = savedPublicKey?.let(::keyFingerprint)
                if (savedFp != null && savedFp != activeFp) {
                    log("Saved public key fp=$savedFp does not match private-derived fp=$activeFp; using private key", LogLevel.WARNING)
                }
                log("Loaded saved key for VIN: $vin fp=$activeFp", LogLevel.INFO)
                return
            }
            log("Saved key is invalid; clearing local pairing data", LogLevel.ERROR)
            secureStorage.clearPairingData(vin)
        } else if (savedVin != null && savedVin != vin) {
            val savedFp = savedPublicKey?.let(::keyFingerprint) ?: "unknown"
            log("Saved key belongs to $savedVin, not $vin; using a fresh key (savedFp=$savedFp)", LogLevel.WARNING)
        }

        if (activeKeyVin != vin || activeKeyPersisted) {
            crypto.generateKeyPair()
            activeKeyVin = vin
            activeKeyPersisted = false
            log("Generated fresh local key for VIN: $vin fp=${currentKeyFingerprint()}", LogLevel.INFO)
        } else {
            log("Reusing fresh in-memory key for VIN: $vin fp=${currentKeyFingerprint()}", LogLevel.DEBUG)
        }
    }

    private fun currentKeyFingerprint(): String = keyFingerprint(crypto.getPublicKeyPoint())

    private fun keyFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return digest.copyOfRange(0, 8).toHexString()
    }

    private fun visiblePairingState(rawState: PairingState? = pairingManager?.state): PairingState {
        val state = rawState ?: PairingState.IDLE
        return if (
            pairingRequestSent &&
            state in listOf(PairingState.IDLE, PairingState.SESSION_INFO_REQUESTED, PairingState.WHITELIST_OPERATION_SENT)
        ) {
            PairingState.WAITING_FOR_NFC_TAP
        } else {
            state
        }
    }

    private val _connectionState = MutableStateFlow(BleConnectionState())
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _logEntries = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val logEntries: StateFlow<List<BleLogEntry>> = _logEntries.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private var vin: String = TeslaBleConstants.DEFAULT_VIN
    private var pollingJob: Job? = null
    private var pairingCheckJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isProcessingSessionInfo = false
    private var pairingRequestSent = false
    private var infotainmentSessionJob: Job? = null
    private var infotainmentRetries = 0
    private val maxInfotainmentRetries = 5
    private var lastVehicleStatusPollAtMs = 0L
    private val lastCarServerPollAtMs = mutableMapOf<VehicleDataCategory, Long>()

    private var inputBuffer = ByteArray(0)
    private var blockLength = 20
    private val writeQueue = ArrayDeque<ByteArray>()
    private var pendingWriteData: ByteArray? = null
    private var pendingWriteOffset = 0
    private var isWriting = false
    private val scanMatchesByAddress = mutableMapOf<String, TeslaScanMatch>()
    private val ignoredNonConnectableAddresses = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val localName = result.scanRecord?.deviceName ?: ""
            val expectedName = TeslaBleConstants.vehicleLocalName(vin)

            if (localName.equals(expectedName, ignoreCase = true)) {
                if (!result.isConnectable) {
                    if (ignoredNonConnectableAddresses.add(device.address)) {
                        log(
                            "Ignoring Tesla beacon that is not connectable: $localName (${device.address}), rssi=${result.rssi}",
                            LogLevel.WARNING
                        )
                    }
                    return
                }

                scanMatchesByAddress[device.address] = TeslaScanMatch(
                    device = device,
                    localName = localName,
                    address = device.address,
                    rssi = result.rssi,
                    connectable = result.isConnectable
                )
                val currentList = _scannedDevices.value.toMutableList()
                if (!currentList.any { it.address == device.address }) {
                    currentList.add(device)
                    _scannedDevices.value = currentList
                    log("Found Tesla vehicle: $localName (${device.address}), rssi=${result.rssi}, connectable=${result.isConnectable}", LogLevel.INFO)
                    stopScan()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            log("BLE Scan failed: errorCode=$errorCode", LogLevel.ERROR)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            log("GATT state changed: status=$status, newState=${gattStateName(newState)}", LogLevel.DEBUG)

            val currentGatt = bluetoothGatt
            if (currentGatt == null || currentGatt !== gatt) {
                Log.d(TAG, "Ignoring callback from stale GATT")
                safeCloseGatt(gatt, "stale callback")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeoutJob?.cancel()
                    log("Connected to GATT server", LogLevel.INFO)
                    _connectionState.update { it.copy(state = SessionState.DISCOVERING_SERVICES) }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    connectTimeoutJob?.cancel()
                    log("Disconnected from device (status=$status)", LogLevel.INFO)
                    stopPolling()
                    pairingManager?.reset()
                    resetBleIoState()
                    safeCloseGatt(gatt, "state disconnected")
                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }
                    _connectionState.update {
                        it.copy(
                            state = SessionState.DISCONNECTED,
                            deviceAddress = "",
                            deviceName = ""
                        )
                    }
                }
                else -> Unit
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "Services discovered: status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Services discovered successfully", LogLevel.INFO)

                val teslaService = gatt.getService(UUID.fromString(TeslaBleConstants.VEHICLE_SERVICE_UUID))
                if (teslaService != null) {
                    log("Found Tesla BLE service", LogLevel.INFO)
                    TeslaBleConstants.logBleUuid(teslaService.uuid.toString(), null)

                    txCharacteristic = teslaService.getCharacteristic(UUID.fromString(TeslaBleConstants.TO_VEHICLE_UUID))
                    rxCharacteristic = teslaService.getCharacteristic(UUID.fromString(TeslaBleConstants.FROM_VEHICLE_UUID))

                    txCharacteristic?.let { TeslaBleConstants.logBleUuid(null, it.uuid.toString()) }
                    rxCharacteristic?.let { TeslaBleConstants.logBleUuid(null, it.uuid.toString()) }

                    if (txCharacteristic == null || rxCharacteristic == null) {
                        log("Missing required characteristics", LogLevel.ERROR)
                        closeCurrentGatt("missing characteristics")
                        _connectionState.update {
                            it.copy(state = SessionState.ERROR, errorMessage = "Missing required characteristics")
                        }
                        return
                    }

                    rxCharacteristic?.let { char ->
                        gatt.setCharacteristicNotification(char, true)
                        val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor?.let {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(it)
                            }
                        } ?: run {
                            Log.w(TAG, "CCCD descriptor not found, requesting MTU directly")
                            _connectionState.update { it.copy(state = SessionState.REQUESTING_MTU) }
                            gatt.requestMtu(TeslaBleConstants.MTU_SIZE)
                        }
                    }
                } else {
                    log("Tesla BLE service not found!", LogLevel.ERROR)
                    closeCurrentGatt("service not found")
                    _connectionState.update {
                        it.copy(state = SessionState.ERROR, errorMessage = "Tesla BLE service not found")
                    }
                }
            } else {
                log("Service discovery failed (status=$status)", LogLevel.ERROR)
                closeCurrentGatt("service discovery failed")
                _connectionState.update {
                    it.copy(state = SessionState.ERROR, errorMessage = "Service discovery failed (status=$status)")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed: mtu=$mtu, status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("MTU negotiated: $mtu bytes", LogLevel.INFO)
                blockLength = mtu - 3
            } else {
                log("MTU negotiation failed, using default", LogLevel.WARNING)
                blockLength = 20
            }

            _connectionState.update { it.copy(state = SessionState.ESTABLISHING_SESSION) }
            startSession()
        }

        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            Log.d(TAG, "RX notification: size=${data.size}")
            Log.d(TAG, "RX hex: ${data.toHexString()}")
            handleIncomingData(data)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "RX notification (new API): size=${value.size}")
            Log.d(TAG, "RX hex: ${value.toHexString()}")
            handleIncomingData(value)
        }

        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write success: ${characteristic.uuid}")
                writeNextChunk(gatt)
            } else {
                Log.e(TAG, "Write failed: ${characteristic.uuid}, status=$status")
                log("BLE write failed (status=$status)", LogLevel.ERROR)
                pendingWriteData = null
                pendingWriteOffset = 0
                drainWriteQueue()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Descriptor write: status=$status, uuid=${descriptor.uuid}")
            if (descriptor.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("Notifications enabled successfully", LogLevel.INFO)
                    _connectionState.update { it.copy(state = SessionState.REQUESTING_MTU) }
                    gatt.requestMtu(TeslaBleConstants.MTU_SIZE)
                } else {
                    Log.e(TAG, "Failed to enable notifications: status=$status")
                    log("Failed to enable notifications", LogLevel.ERROR)
                    _connectionState.update { it.copy(state = SessionState.REQUESTING_MTU) }
                    gatt.requestMtu(TeslaBleConstants.MTU_SIZE)
                }
            }
        }
    }

    private fun handleIncomingData(data: ByteArray) {
        inputBuffer += data

        while (inputBuffer.size >= 2) {
            val msgLength = ((inputBuffer[0].toInt() and 0xFF) shl 8) or (inputBuffer[1].toInt() and 0xFF)
            if (msgLength > TeslaBleConstants.MAX_BLE_MESSAGE_SIZE) {
                Log.e(TAG, "Message too large: $msgLength, resetting buffer")
                inputBuffer = ByteArray(0)
                return
            }

            if (inputBuffer.size < 2 + msgLength) {
                break
            }

            val messageBytes = inputBuffer.copyOfRange(2, 2 + msgLength)
            inputBuffer = inputBuffer.copyOfRange(2 + msgLength, inputBuffer.size)

            Log.d(TAG, "Complete message received: $msgLength bytes")
            Log.d(TAG, "Message hex: ${messageBytes.toHexString()}")

            handleMessage(messageBytes)
        }
    }

    private fun handleMessage(payload: ByteArray) {
        val message = TeslaBleMessage.parsePayload(payload)
        if (message != null) {
            handleRoutableMessage(message)
        } else {
            Log.w(TAG, "Failed to parse as RoutableMessage, trying FromVCSECMessage")
            tryHandleFromVCSECMessage(payload)
        }
    }

    private fun tryHandleFromVCSECMessage(payload: ByteArray) {
        val fromVCSEC = pairingManager?.handleFromVCSECMessage(payload)
        if (fromVCSEC != null) {
            log("Received FromVCSECMessage directly", LogLevel.INFO)
            _connectionState.update {
                it.copy(
                    pairingState = visiblePairingState(),
                    lastUpdateTime = System.currentTimeMillis()
                ).withSnapshot()
            }
            if (fromVCSEC.hasVehicleStatus()) {
                _connectionState.update {
                    it.copy(vehicleStatus = fromVCSEC.vehicleStatus).withSnapshot()
                }
            }
        } else {
            Log.e(TAG, "Failed to parse as FromVCSECMessage either, hex: ${payload.toHexString()}")
            log("Failed to parse message", LogLevel.ERROR)
        }
    }

    private fun handleRoutableMessage(message: RoutableMessage) {
        val hasSessionInfo = message.hasSessionInfo()
        val hasPayload = message.protobufMessageAsBytes.toByteArray().isNotEmpty()
        val hasSigData = message.hasSignatureData()
        val domain = inferMessageDomain(message)
        Log.d(TAG, "RoutableMessage: sessionInfo=$hasSessionInfo, hasPayload=$hasPayload, hasSig=$hasSigData, domain=$domain")

        // --- SessionInfo handling (domain-aware) ---
        // SessionInfo responses always come as DOMAIN_BROADCAST, so we use
        // the pending domain tracked by PairingManager.
        if (hasSessionInfo) {
            if (isProcessingSessionInfo) {
                Log.d(TAG, "Already processing SessionInfo, skipping duplicate")
                return
            }
            val expectedDomain = pairingManager?.getPendingSessionInfoDomain(message.requestUuid.toByteArray())
            val isInfotainment = expectedDomain == Domain.DOMAIN_INFOTAINMENT

            val alreadyAuth = if (isInfotainment) {
                pairingManager?.isDomainAuthenticated(Domain.DOMAIN_INFOTAINMENT) == true
            } else {
                pairingManager?.isAuthenticated() == true
            }
            if (alreadyAuth) {
                Log.d(TAG, "Session already established for domain=$expectedDomain, ignoring SessionInfo")
                pairingManager?.handleSessionInfo(message)
                return
            }

            isProcessingSessionInfo = true
            log("Received SessionInfo for domain=$expectedDomain", LogLevel.INFO)
            val result = pairingManager?.handleSessionInfo(message)
            isProcessingSessionInfo = false

            if (isInfotainment) {
                handleInfotainmentSessionResult(result)
            } else {
                handleVcsecSessionResult(result)
            }
            return
        }

        // --- Encrypted message handling (domain-aware) ---
        if (hasPayload && hasSigData) {
            log("Received encrypted RoutableMessage (domain=$domain)", LogLevel.INFO)
            val hasKey = pairingManager?.hasDomainSessionKey(domain) == true
            if (!hasKey) {
                log("No session key for domain=$domain, cannot decrypt", LogLevel.WARNING)
                return
            }
            val decrypted = pairingManager?.decryptMessage(message)
            if (decrypted == null) {
                log("Decryption failed for domain=$domain", LogLevel.ERROR)
                return
            }
            log("Decryption successful for domain=$domain", LogLevel.INFO)
            routeDecryptedPayload(decrypted, domain)
            return
        }

        // --- Unencrypted message handling (domain-aware) ---
        if (hasPayload) {
            log("Received unencrypted RoutableMessage (domain=$domain)", LogLevel.DEBUG)
            val protobufBytes = message.protobufMessageAsBytes.toByteArray()
            Log.d(TAG, "Unencrypted payload hex: ${protobufBytes.toHexString()}, size=${protobufBytes.size}")
            if (protobufBytes.isNotEmpty()) {
                if (domain == Domain.DOMAIN_INFOTAINMENT) {
                    handleCarServerPayload(protobufBytes)
                } else {
                    handleVcsecPayload(protobufBytes)
                }
            }
        }

        // --- Status/fault check ---
        val msgStatus = message.signedMessageStatus
        if (msgStatus.operationStatus != com.tesla.generated.universalmessage.OperationStatus_E.OPERATIONSTATUS_OK ||
            msgStatus.signedMessageFault != com.tesla.generated.universalmessage.MessageFault_E.MESSAGEFAULT_ERROR_NONE) {
            val fault = msgStatus.signedMessageFault
            if (fault != com.tesla.generated.universalmessage.MessageFault_E.MESSAGEFAULT_ERROR_NONE) {
                log("Message fault: $fault", LogLevel.ERROR)
                _connectionState.update { it.copy(errorMessage = "Fault: $fault") }
            }
        }
    }

    private fun inferMessageDomain(message: RoutableMessage): Domain {
        pairingManager?.inferResponseDomain(message)?.let { return it }
        if (message.hasFromDestination() && message.fromDestination.hasDomain()) {
            return message.fromDestination.domain
        }
        if (message.hasToDestination() && message.toDestination.hasDomain()) {
            return message.toDestination.domain
        }
        return pairingManager?.getPendingSessionInfoDomain(message.requestUuid.toByteArray())
            ?: Domain.DOMAIN_VEHICLE_SECURITY
    }

    private fun BleConnectionState.withSnapshot(): BleConnectionState {
        return copy(
            snapshot = TeslaVehicleSnapshot.from(
                vehicleStatus = vehicleStatus,
                driveState = driveState,
                chargeState = chargeState,
                climateState = climateState,
                carClosuresState = carServerClosuresState,
                tirePressureState = tirePressureState,
                mediaState = mediaState
            )
        )
    }

    private fun handleVcsecSessionResult(result: PairingManager.SessionInfoResult?) {
        when (result) {
            PairingManager.SessionInfoResult.KEY_NOT_ON_WHITELIST -> {
                if (!pairingRequestSent) {
                    log("Key fp=${currentKeyFingerprint()} is not on whitelist, sending pairing request...", LogLevel.INFO)
                    _connectionState.update {
                        it.copy(
                            state = SessionState.AUTHENTICATED,
                            pairingState = PairingState.WHITELIST_OPERATION_SENT
                        )
                    }
                    pairingRequestSent = true
                    sendPairingRequest()
                    pairingManager?.markWaitingForNfcTap()
                    _connectionState.update {
                        it.copy(
                            state = SessionState.AUTHENTICATED,
                            pairingState = PairingState.WAITING_FOR_NFC_TAP
                        )
                    }
                    startPairingCheck()
                } else {
                    log("Still waiting for NFC tap for key fp=${currentKeyFingerprint()}...", LogLevel.DEBUG)
                    _connectionState.update {
                        it.copy(
                            state = SessionState.AUTHENTICATED,
                            pairingState = PairingState.WAITING_FOR_NFC_TAP
                        )
                    }
                }
            }
            PairingManager.SessionInfoResult.SUCCESS -> {
                pairingCheckJob?.cancel()
                val wasPersisted = activeKeyPersisted
                val fp = currentKeyFingerprint()
                secureStorage.saveKeyPair(
                    vin,
                    crypto.getPublicKeyPoint(),
                    crypto.getPrivateKeyEncoded(),
                    _connectionState.value.deviceAddress
                )
                activeKeyVin = vin
                activeKeyPersisted = true
                if (wasPersisted) {
                    log("VCSEC authorized by vehicle with saved key fp=$fp", LogLevel.INFO)
                } else {
                    log("VCSEC authorized by vehicle with fresh/in-memory key fp=$fp", LogLevel.WARNING)
                }
                log("Key pair saved to local storage fp=$fp", LogLevel.INFO)
                _connectionState.update {
                    it.copy(
                        state = SessionState.AUTHENTICATED,
                        pairingState = PairingState.PAIRED
                    )
                }
                log("VCSEC session established; vehicle accepted the current key.", LogLevel.INFO)
                startPolling()
                // Start Infotainment session after VCSEC is up
                startInfotainmentSession()
            }
            else -> {
                log("VCSEC session establishment failed", LogLevel.ERROR)
                _connectionState.update {
                    it.copy(
                        state = SessionState.ERROR,
                        errorMessage = "Session establishment failed",
                        pairingState = PairingState.ERROR
                    )
                }
            }
        }
    }

    private fun handleInfotainmentSessionResult(result: PairingManager.SessionInfoResult?) {
        when (result) {
            PairingManager.SessionInfoResult.SUCCESS -> {
                infotainmentRetries = 0
                log("Infotainment session established!", LogLevel.INFO)
                _connectionState.update { it.copy(infotainmentSessionActive = true, infotainmentError = null) }
            }
            else -> {
                infotainmentRetries++
                val errMsg = "Infotainment session failed (attempt $infotainmentRetries/$maxInfotainmentRetries)"
                log(errMsg, LogLevel.WARNING)
                if (infotainmentRetries < maxInfotainmentRetries) {
                    log("Retrying Infotainment session in 3s...", LogLevel.INFO)
                    infotainmentSessionJob?.cancel()
                    infotainmentSessionJob = scope.launch {
                        delay(3000)
                        if (pairingManager?.isAuthenticated() == true) {
                            requestInfotainmentSession()
                        }
                    }
                } else {
                    log("Max Infotainment retries reached", LogLevel.ERROR)
                    _connectionState.update { it.copy(infotainmentError = "Failed after $maxInfotainmentRetries attempts") }
                }
            }
        }
    }

    private fun routeDecryptedPayload(decrypted: RoutableMessage, domain: Domain) {
        val payload = decrypted.protobufMessageAsBytes.toByteArray()
        if (payload.isEmpty()) {
            log("Decrypted message has no payload for domain=$domain", LogLevel.WARNING)
            return
        }
        if (domain == Domain.DOMAIN_INFOTAINMENT) {
            handleCarServerPayload(payload)
        } else {
            handleVcsecPayload(payload)
        }
    }

    private fun handleCarServerPayload(payload: ByteArray) {
        val vehicleData = pairingManager?.handleCarServerResponse(payload)
        if (vehicleData != null) {
            _connectionState.update {
                val updated = it.copy(
                    driveState = if (vehicleData.hasDriveState()) vehicleData.driveState else it.driveState,
                    chargeState = if (vehicleData.hasChargeState()) vehicleData.chargeState else it.chargeState,
                    climateState = if (vehicleData.hasClimateState()) vehicleData.climateState else it.climateState,
                    carServerClosuresState = if (vehicleData.hasClosuresState()) vehicleData.closuresState else it.carServerClosuresState,
                    tirePressureState = if (vehicleData.hasTirePressureState()) vehicleData.tirePressureState else it.tirePressureState,
                    mediaState = if (vehicleData.hasMediaState()) vehicleData.mediaState else it.mediaState,
                    lastUpdateTime = System.currentTimeMillis()
                )
                updated.withSnapshot()
            }
            log("CarServer vehicle data updated", LogLevel.INFO)
        }
    }

    private fun handleVcsecPayload(payload: ByteArray) {
        val fromVCSEC = pairingManager?.handleFromVCSECMessage(payload)
        if (fromVCSEC != null) {
            _connectionState.update {
                it.copy(
                    pairingState = visiblePairingState(),
                    lastUpdateTime = System.currentTimeMillis()
                ).withSnapshot()
            }
            if (fromVCSEC.hasVehicleStatus()) {
                _connectionState.update { it.copy(vehicleStatus = fromVCSEC.vehicleStatus).withSnapshot() }
                log("VCSEC vehicle status updated", LogLevel.INFO)
            }
            if (fromVCSEC.hasCommandStatus()) {
                log("VCSEC command status: ${fromVCSEC.commandStatus.operationStatus}", LogLevel.INFO)
            }
            if (fromVCSEC.hasWhitelistInfo()) {
                Log.d(TAG, "VCSEC WhitelistInfo: entries=${fromVCSEC.whitelistInfo.numberOfEntries}")
            }
        } else {
            Log.d(TAG, "Payload not a FromVCSECMessage")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(vinInput: String? = null) {
        if (vinInput != null) {
            vin = vinInput
        }

        if (bluetoothAdapter?.isEnabled != true) {
            log("Bluetooth is not enabled", LogLevel.ERROR)
            return
        }

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            log("BLE Scanner not available", LogLevel.ERROR)
            return
        }

        _scannedDevices.value = emptyList()
        scanMatchesByAddress.clear()
        ignoredNonConnectableAddresses.clear()
        val expectedName = TeslaBleConstants.vehicleLocalName(vin)
        log("Starting BLE scan for Tesla: $expectedName", LogLevel.INFO)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner?.startScan(null, settings, scanCallback)
            log("BLE scan started (no hardware filter, matching by Local Name)", LogLevel.INFO)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            log("Failed to start scan: ${e.message}", LogLevel.ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            log("BLE scan stopped", LogLevel.INFO)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, vinInput: String? = null) {
        if (vinInput != null) {
            vin = vinInput
        }

        val scanMatch = scanMatchesByAddress[device.address]
        val expectedName = TeslaBleConstants.vehicleLocalName(vin)
        if (scanMatch == null || !scanMatch.localName.equals(expectedName, ignoreCase = true) || !scanMatch.connectable) {
            val message = "Selected device is not a current connectable Tesla beacon for this VIN. Scan again."
            log(message, LogLevel.ERROR)
            _connectionState.update { it.copy(state = SessionState.DISCONNECTED, errorMessage = message) }
            return
        }

        stopScan()
        connectTimeoutJob?.cancel()
        stopPolling()
        closeCurrentGatt("before connect")
        resetBleIoState()
        prepareKeyForVin(vin)

        log("Connecting to ${scanMatch.localName} (${scanMatch.address}), rssi=${scanMatch.rssi}", LogLevel.INFO)

        _connectionState.update {
            it.copy(
                state = SessionState.CONNECTING,
                vin = vin,
                deviceAddress = scanMatch.address,
                deviceName = scanMatch.localName,
                errorMessage = null
            )
        }

        pairingManager = PairingManager(crypto, vin)
        pairingManager?.setLogCallback { message, level ->
            log(message, level)
        }
        bluetoothDevice = device
        inputBuffer = ByteArray(0)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        startConnectTimeout(device.address)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        log("Disconnecting...", LogLevel.INFO)
        connectTimeoutJob?.cancel()
        stopPolling()
        infotainmentSessionJob?.cancel()
        infotainmentSessionJob = null
        infotainmentRetries = 0
        resetPollingSchedule()
        pairingManager?.reset()

        closeCurrentGatt("manual disconnect")
        bluetoothGatt = null
        bluetoothDevice = null
        resetBleIoState()
        isProcessingSessionInfo = false
        pairingRequestSent = false

        stopPairingCheck()
        stopPolling()

        _connectionState.update { BleConnectionState() }
    }

    private fun startConnectTimeout(deviceAddress: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(connectTimeoutMs)
            val state = _connectionState.value
            if (state.state == SessionState.CONNECTING && state.deviceAddress == deviceAddress) {
                log("Connection timeout after ${connectTimeoutMs / 1000}s; closing GATT", LogLevel.WARNING)
                closeCurrentGatt("connect timeout")
                resetBleIoState()
                _connectionState.update {
                    it.copy(
                        state = SessionState.DISCONNECTED,
                        deviceAddress = "",
                        deviceName = "",
                        errorMessage = "Connection timeout; scan again"
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt(reason: String) {
        val gatt = bluetoothGatt ?: return
        Log.d(TAG, "Closing current GATT ($reason)")
        try {
            gatt.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "GATT disconnect failed ($reason)", e)
        }
        safeCloseGatt(gatt, reason)
        bluetoothGatt = null
    }

    private fun safeCloseGatt(gatt: BluetoothGatt?, reason: String) {
        if (gatt == null) return
        try {
            gatt.close()
        } catch (e: Exception) {
            Log.w(TAG, "GATT close failed ($reason)", e)
        }
    }

    private fun resetBleIoState() {
        txCharacteristic = null
        rxCharacteristic = null
        inputBuffer = ByteArray(0)
        writeQueue.clear()
        pendingWriteData = null
        pendingWriteOffset = 0
        isWriting = false
    }

    private fun gattStateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> state.toString()
    }

    fun clearPairing() {
        log("Clearing pairing data", LogLevel.INFO)
        deleteSavedKey()
    }

    private fun startSession() {
        log("Starting session with VCSEC domain", LogLevel.INFO)
        val request = pairingManager?.createSessionInfoRequest()
        if (request != null) {
            sendMessage(request)
            log("SessionInfoRequest sent, waiting for response...", LogLevel.INFO)
        } else {
            Log.e(TAG, "Failed to create SessionInfoRequest")
            log("Failed to create SessionInfoRequest", LogLevel.ERROR)
        }
    }

    private fun startInfotainmentSession() {
        log("Starting Infotainment domain session", LogLevel.INFO)
        infotainmentRetries = 0
        pairingManager?.initInfotainmentCrypto()
        requestInfotainmentSession()
    }

    private fun requestInfotainmentSession() {
        try {
            val request = pairingManager?.createSessionInfoRequest(Domain.DOMAIN_INFOTAINMENT)
            if (request != null) {
                sendMessage(request)
                log("Infotainment SessionInfoRequest sent", LogLevel.INFO)
            } else {
                log("Failed to create Infotainment SessionInfoRequest", LogLevel.ERROR)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Infotainment session", e)
            log("Infotainment session request failed: ${e.message}", LogLevel.ERROR)
        }
    }

    private fun sendPairingRequest() {
        log("Sending pairing request (ToVCSECMessage, unencrypted)", LogLevel.INFO)
        val payload = pairingManager?.createPairingRequest()
        if (payload != null) {
            val framed = TeslaBleMessage.encodeRaw(payload)
            log("TX: ToVCSECMessage, total size: ${framed.size}", LogLevel.INFO)
            Log.d(TAG, "TX hex: ${framed.toHexString()}")
            sendRaw(framed)
            log("Pairing request sent! Tap your card key on the B-pillar NFC reader.", LogLevel.INFO)
        } else {
            log("Failed to create pairing request", LogLevel.ERROR)
        }
    }

    private fun startPairingCheck() {
        pairingCheckJob?.cancel()
        pairingCheckJob = scope.launch {
            delay(2000)
            while (isActive) {
                log("Checking pairing status...", LogLevel.INFO)
                val request = pairingManager?.createSessionInfoRequest()
                if (request != null) {
                    sendMessage(request)
                }
                delay(3000)
            }
        }
    }

    private fun stopPairingCheck() {
        pairingCheckJob?.cancel()
        pairingCheckJob = null
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) {
            Log.d(TAG, "Polling already active, skipping restart")
            return
        }
        resetPollingSchedule()
        log(
            "Starting polling scheduler (tick=${schedulerTickMs}ms, VCSEC=${vcsecPollingIntervalMs}ms, CarServer=${carServerPollSummary()})",
            LogLevel.INFO
        )

        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && pairingManager?.isAuthenticated() == true) {
                val now = SystemClock.elapsedRealtime()

                if (lastVehicleStatusPollAtMs == 0L || now - lastVehicleStatusPollAtMs >= vcsecPollingIntervalMs) {
                    if (requestVehicleStatus()) {
                        lastVehicleStatusPollAtMs = now
                    }
                }

                if (pairingManager?.isDomainAuthenticated(Domain.DOMAIN_INFOTAINMENT) == true) {
                    val category = nextDueCarServerCategory(now)
                    if (category != null && requestCarServerData(category)) {
                        lastCarServerPollAtMs[category] = now
                    }
                }

                delay(schedulerTickMs)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        log("Vehicle status polling stopped", LogLevel.INFO)
    }

    private fun resetPollingSchedule() {
        lastVehicleStatusPollAtMs = 0L
        lastCarServerPollAtMs.clear()
    }

    private fun carServerPollSummary(): String =
        carServerPollSpecs.joinToString { "${it.category}=${it.intervalMs}ms" }

    private fun nextDueCarServerCategory(nowMs: Long): VehicleDataCategory? {
        return carServerPollSpecs.firstOrNull { spec ->
            val lastPollAt = lastCarServerPollAtMs[spec.category]
            lastPollAt == null || nowMs - lastPollAt >= spec.intervalMs
        }?.category
    }

    private fun requestVehicleStatus(): Boolean {
        if (pairingManager?.hasSessionKey() != true) {
            log("No session key, cannot request status", LogLevel.ERROR)
            return false
        }
        val request = pairingManager?.createVehicleStatusRequest()
        if (request != null) {
            try {
                val encrypted = pairingManager?.encryptMessage(request)
                if (encrypted != null) {
                    log("TX: Encrypted VehicleStatus request (VCSEC), size: ${encrypted.size}", LogLevel.DEBUG)
                    sendRaw(encrypted)
                    return true
                } else {
                    log("VCSEC encryption returned null", LogLevel.ERROR)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encrypt VCSEC vehicle status request", e)
                log("VCSEC encryption failed: ${e.message}", LogLevel.ERROR)
            }
        } else {
            log("Failed to create VehicleStatus request", LogLevel.ERROR)
        }
        return false
    }

    private fun requestCarServerData(category: VehicleDataCategory): Boolean {
        if (pairingManager?.hasDomainSessionKey(Domain.DOMAIN_INFOTAINMENT) != true) {
            return false
        }
        if (writeQueue.size >= maxPollingWriteQueueSize) {
            Log.d(TAG, "Skipping CarServer poll [$category], write queue=${writeQueue.size}")
            return false
        }
        try {
            val request = pairingManager?.createGetVehicleDataRequest(category)
            if (request != null) {
                val encrypted = pairingManager?.encryptMessage(request)
                if (encrypted != null) {
                    log("TX: Encrypted GetVehicleData [$category] (Infotainment), size: ${encrypted.size}", LogLevel.DEBUG)
                    sendRaw(encrypted)
                    return true
                } else {
                    log("Infotainment encryption returned null", LogLevel.ERROR)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send CarServer request", e)
            log("CarServer request failed: ${e.message}", LogLevel.ERROR)
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun sendMessage(message: RoutableMessage) {
        val data = TeslaBleMessage.encode(message)
        log("TX: RoutableMessage, total size: ${data.size}", LogLevel.INFO)
        Log.d(TAG, "TX hex: ${data.toHexString()}")
        sendRaw(data)
    }

    @SuppressLint("MissingPermission")
    private fun sendRaw(data: ByteArray) {
        if (isWriting) {
            writeQueue.addLast(data)
            Log.d(TAG, "Write in progress, queued message (queue=${writeQueue.size})")
            return
        }
        txCharacteristic?.let { char ->
            isWriting = true
            pendingWriteData = data
            pendingWriteOffset = 0
            writeNextChunk(bluetoothGatt ?: return)
        } ?: run {
            Log.e(TAG, "TX characteristic not available")
            log("TX characteristic not available", LogLevel.ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeQueue.isNotEmpty()) {
            val next = writeQueue.removeFirst()
            txCharacteristic?.let { char ->
                pendingWriteData = next
                pendingWriteOffset = 0
                writeNextChunk(bluetoothGatt ?: return)
            } ?: run {
                isWriting = false
            }
        } else {
            isWriting = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextChunk(gatt: BluetoothGatt) {
        val data = pendingWriteData ?: return
        val char = txCharacteristic ?: return

        if (pendingWriteOffset >= data.size) {
            Log.d(TAG, "Write complete: ${data.size} bytes")
            pendingWriteData = null
            pendingWriteOffset = 0
            drainWriteQueue()
            return
        }

        val end = minOf(pendingWriteOffset + blockLength, data.size)
        val chunk = data.copyOfRange(pendingWriteOffset, end)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (result != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed at offset $pendingWriteOffset, result=$result")
                log("BLE write failed (result=$result)", LogLevel.ERROR)
                pendingWriteData = null
                pendingWriteOffset = 0
                drainWriteQueue()
                return
            }
        } else {
            @Suppress("DEPRECATION")
            char.setValue(chunk)
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val success = gatt.writeCharacteristic(char)
            if (!success) {
                Log.e(TAG, "Write failed at offset $pendingWriteOffset")
                log("BLE write failed", LogLevel.ERROR)
                pendingWriteData = null
                pendingWriteOffset = 0
                drainWriteQueue()
                return
            }
        }

        Log.d(TAG, "TX chunk: offset=$pendingWriteOffset, size=${chunk.size}")
        pendingWriteOffset = end
    }

    private fun log(message: String, level: LogLevel) {
        val entry = BleLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message
        )

        _logEntries.value = (_logEntries.value + entry).takeLast(500)

        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.WARNING -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
        }
    }

    fun release() {
        scope.cancel()
        disconnect()
    }
}
