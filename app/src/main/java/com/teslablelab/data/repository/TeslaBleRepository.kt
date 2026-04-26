package com.teslablelab.data.repository

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.compat.annotation.UnsupportedAppUsage
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.jakewharton.timber.Timber
import com.teslablelab.domain.crypto.TeslaCrypto
import com.teslablelab.domain.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

data class BleConnectionState(
    val state: SessionState = SessionState.DISCONNECTED,
    val vin: String = "",
    val deviceAddress: String = "",
    val deviceName: String = "",
    val errorMessage: String? = null,
    val lastUpdateTime: Long = 0,
    val vehicleState: VehicleState? = null,
    val pairingState: PairingState = PairingState.IDLE
)

data class BleLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.DEBUG,
    val message: String,
    val details: String? = null
)

enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR
}

class TeslaBleRepository(
    private val context: Context
) {
    private val TAG = "TeslaBleRepository"

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val crypto = TeslaCrypto()
    private val vehicleStateDecoder = VehicleStateDecoder()
    private var pairingManager: PairingManager? = null

    private val _connectionState = MutableStateFlow(BleConnectionState())
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _logEntries = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val logEntries: StateFlow<List<BleLogEntry>> = _logEntries.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var vin: String = ""
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentDeviceAddress: String = ""
    private var currentDeviceName: String = ""

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Timber.tag(TAG).d("Scan result: ${device.name ?: "Unknown"} (${device.address})")

            val currentList = _scannedDevices.value.toMutableList()
            if (!currentList.any { it.address == device.address }) {
                currentList.add(device)
                _scannedDevices.value = currentList
                log("Found device: ${device.name ?: "Unknown"} (${device.address})", LogLevel.INFO)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.tag(TAG).e("Scan failed with error code: $errorCode")
            log("BLE Scan failed: errorCode=$errorCode", LogLevel.ERROR)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Timber.tag(TAG).d("Connection state changed: status=$status, newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected to GATT server", LogLevel.INFO)
                    _connectionState.update { it.copy(state = SessionState.DISCONNECTING) }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.tag(TAG).d("Disconnected from GATT server")
                    log("Disconnected from device", LogLevel.INFO)
                    stopPolling()
                    pairingManager?.reset()
                    _connectionState.update {
                        it.copy(
                            state = SessionState.DISCONNECTED,
                            deviceAddress = "",
                            deviceName = ""
                        )
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Timber.tag(TAG).d("Services discovered: status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Services discovered successfully", LogLevel.INFO)
                _connectionState.update { it.copy(state = SessionState.DISCOVERING_SERVICES) }

                val teslaService = gatt.getService(UUID.fromString(TeslaBleConstants.TESLA_BLE_SERVICE_UUID))
                if (teslaService != null) {
                    log("Found Tesla BLE service", LogLevel.INFO)
                    TeslaBleConstants.logBleUuid(
                        teslaService.uuid.toString(),
                        null
                    )

                    readCharacteristic = teslaService.getCharacteristic(UUID.fromString(TeslaBleConstants.TESLA_BLE_CHARACTERISTIC_WRITE_UUID))
                    notifyCharacteristic = teslaService.getCharacteristic(UUID.fromString(TeslaBleConstants.TESLA_BLE_CHARACTERISTIC_NOTIFY_UUID))
                    writeCharacteristic = teslaService.getCharacteristic(UUID.fromString(TeslaBleConstants.TESLA_BLE_CHARACTERISTIC_WRITE_WO_RESP_UUID))

                    readCharacteristic?.let { char ->
                        TeslaBleConstants.logBleUuid(null, char.uuid.toString())
                    }
                    notifyCharacteristic?.let { char ->
                        TeslaBleConstants.logBleUuid(null, char.uuid.toString())
                    }
                    writeCharacteristic?.let { char ->
                        TeslaBleConstants.logBleUuid(null, char.uuid.toString())
                    }

                    _connectionState.update { it.copy(state = SessionState.REQUESTING_MTU) }
                    gatt.requestMtu(TeslaBleConstants.MTU_SIZE)
                } else {
                    log("Tesla BLE service not found", LogLevel.ERROR)
                    _connectionState.update {
                        it.copy(
                            state = SessionState.ERROR,
                            errorMessage = "Tesla BLE service not found"
                        )
                    }
                }
            } else {
                log("Service discovery failed", LogLevel.ERROR)
                _connectionState.update {
                    it.copy(
                        state = SessionState.ERROR,
                        errorMessage = "Service discovery failed"
                    )
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Timber.tag(TAG).d("MTU changed: mtu=$mtu, status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("MTU negotiated: $mtu bytes", LogLevel.INFO)
                _connectionState.update { it.copy(state = SessionState.PAIRING) }
                startPairing()
            } else {
                log("MTU negotiation failed, trying with default", LogLevel.WARNING)
                _connectionState.update { it.copy(state = SessionState.PAIRING) }
                startPairing()
            }
        }

        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                Timber.tag(TAG).d("Characteristic read: ${characteristic.uuid}, size=${data.size}")
                Timber.tag(TAG).d("Data hex: ${data.toHexString()}")
                handleIncomingData(data)
            }
        }

        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.tag(TAG).d("Characteristic write success: ${characteristic.uuid}")
            } else {
                Timber.tag(TAG).e("Characteristic write failed: ${characteristic.uuid}, status=$status")
            }
        }

        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            Timber.tag(TAG).d("Characteristic changed: ${characteristic.uuid}, size=${data.size}")
            Timber.tag(TAG).d("Data hex: ${data.toHexString()}")
            handleIncomingData(data)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Timber.tag(TAG).d("Characteristic changed (new API): ${characteristic.uuid}, size=${value.size}")
            Timber.tag(TAG).d("Data hex: ${value.toHexString()}")
            handleIncomingData(value)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
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
        log("Starting BLE scan for Tesla devices", LogLevel.INFO)

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(TeslaBleConstants.TESLA_BLE_SERVICE_UUID)))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            log("BLE scan started", LogLevel.INFO)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start scan")
            log("Failed to start scan: ${e.message}", LogLevel.ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            log("BLE scan stopped", LogLevel.INFO)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to stop scan")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, vinInput: String) {
        vin = vinInput
        currentDeviceAddress = device.address
        currentDeviceName = device.name ?: "Tesla"

        log("Connecting to ${currentDeviceName} ($currentDeviceAddress)", LogLevel.INFO)

        _connectionState.update {
            it.copy(
                state = SessionState.CONNECTING,
                vin = vin,
                deviceAddress = currentDeviceAddress,
                deviceName = currentDeviceName
            )
        }

        pairingManager = PairingManager(crypto, vin)
        bluetoothDevice = device
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        log("Disconnecting...", LogLevel.INFO)
        stopPolling()

        pollingJob?.cancel()
        pollingJob = null

        pairingManager?.reset()

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during disconnect")
        }

        bluetoothGatt = null
        bluetoothDevice = null
        readCharacteristic = null
        notifyCharacteristic = null
        writeCharacteristic = null

        _connectionState.update {
            BleConnectionState()
        }
    }

    fun clearPairing() {
        log("Clearing pairing data", LogLevel.INFO)
        disconnect()
    }

    private fun startPairing() {
        log("Starting pairing process", LogLevel.INFO)
        pairingManager?.startPairing()

        val publicKeyPoint = crypto.getPublicKeyPoint()
        log("Our public key: ${publicKeyPoint.toHexString()}", LogLevel.DEBUG)

        val handshakeStartPayload = ByteArray(64)
        System.arraycopy(publicKeyPoint, 0, handshakeStartPayload, 0, minOf(publicKeyPoint.size, 64))

        val message = TeslaBleMessage.createMessage(
            TeslaBleConstants.MESSAGE_TYPE_HANDSHAKE_START,
            sessionId = 0,
            payload = handshakeStartPayload
        )

        sendMessage(message)
    }

    private fun handleIncomingData(data: ByteArray) {
        if (data.isEmpty()) return

        val message = TeslaBleMessage.parse(data) ?: run {
            log("Failed to parse incoming message", LogLevel.ERROR)
            return
        }

        log("Received message: ${TeslaBleConstants.getMessageTypeName(message.messageType)}", LogLevel.DEBUG)

        when (message.messageType) {
            TeslaBleConstants.MESSAGE_TYPE_HANDSHAKE_ACK -> {
                handleHandshakeAck(message.payload)
            }
            TeslaBleConstants.MESSAGE_TYPE_SIGNATURE_CHALLENGE -> {
                handleSignatureChallenge(message)
            }
            TeslaBleConstants.MESSAGE_TYPE_KEY_EXCHANGE_ACK -> {
                handleKeyExchangeAck(message)
            }
            TeslaBleConstants.MESSAGE_TYPE_SESSION_RESPONSE -> {
                handleSessionResponse(message)
            }
            TeslaBleConstants.MESSAGE_TYPE_CRYPTO -> {
                handleCryptoMessage(message)
            }
            TeslaBleConstants.MESSAGE_TYPE_SUBSCRIBE_RESPONSE -> {
                handleSubscribeResponse(message)
            }
            else -> {
                log("Unhandled message type: ${TeslaBleConstants.getMessageTypeName(message.messageType)}", LogLevel.WARNING)
            }
        }
    }

    private fun handleHandshakeAck(payload: ByteArray) {
        log("Received HANDSHAKE_ACK", LogLevel.INFO)
        log("Payload: ${payload.toHexString()}", LogLevel.DEBUG)

        pairingManager?.createKeyExchangeMessage()?.let { sendMessage(it) }
    }

    private fun handleSignatureChallenge(message: TeslaBleMessage) {
        log("Received SIGNATURE_CHALLENGE", LogLevel.INFO)

        val signatureResponse = pairingManager?.handleSignatureChallenge(message.payload)
        if (signatureResponse != null) {
            val responseMessage = TeslaBleMessage.createMessage(
                TeslaBleConstants.MESSAGE_TYPE_SIGNATURE_RESPONSE,
                sessionId = 0,
                payload = signatureResponse
            )
            sendMessage(responseMessage)
            log("Sent SIGNATURE_RESPONSE", LogLevel.INFO)
        }
    }

    private fun handleKeyExchangeAck(message: TeslaBleMessage) {
        log("Received KEY_EXCHANGE_ACK", LogLevel.INFO)

        if (pairingManager?.handleKeyExchangeAck(message.payload) == true) {
            val sessionRequest = pairingManager?.createSessionRequestMessage()
            if (sessionRequest != null) {
                sendMessage(sessionRequest)
                log("Sent SESSION_REQUEST", LogLevel.INFO)
            }
        } else {
            log("Key exchange verification failed", LogLevel.ERROR)
            _connectionState.update {
                it.copy(
                    state = SessionState.ERROR,
                    errorMessage = "Key exchange verification failed"
                )
            }
        }
    }

    private fun handleSessionResponse(message: TeslaBleMessage) {
        log("Received SESSION_RESPONSE", LogLevel.INFO)

        if (pairingManager?.handleSessionResponse(message.payload) == true) {
            log("Session established successfully!", LogLevel.INFO)
            _connectionState.update {
                it.copy(
                    state = SessionState.AUTHENTICATED,
                    pairingState = PairingState.PAIRED
                )
            }

            val subscribeMessage = pairingManager?.createSubscribeMessage()
            if (subscribeMessage != null) {
                sendMessage(subscribeMessage)
                log("Sent SUBSCRIBE message", LogLevel.INFO)
            }
        } else {
            log("Session establishment failed", LogLevel.ERROR)
            _connectionState.update {
                it.copy(
                    state = SessionState.ERROR,
                    errorMessage = "Session establishment failed"
                )
            }
        }
    }

    private fun handleCryptoMessage(message: TeslaBleMessage) {
        val decrypted = pairingManager?.decryptIncomingMessage(message)
        if (decrypted != null) {
            handleDecryptedMessage(decrypted)
        } else {
            log("Failed to decrypt crypto message", LogLevel.ERROR)
        }
    }

    private fun handleDecryptedMessage(message: TeslaBleMessage) {
        log("Decrypted message: ${TeslaBleConstants.getMessageTypeName(message.messageType)}", LogLevel.DEBUG)

        when (message.messageType) {
            TeslaBleConstants.MESSAGE_TYPE_SUBSCRIBE_RESPONSE -> {
                handleSubscribeResponse(message)
            }
            else -> {
                log("Unhandled decrypted message type: ${TeslaBleConstants.getMessageTypeName(message.messageType)}", LogLevel.WARNING)
            }
        }
    }

    private fun handleSubscribeResponse(message: TeslaBleMessage) {
        log("Received SUBSCRIBE_RESPONSE", LogLevel.INFO)

        if (message.payload.size >= 4) {
            val status = ByteBuffer.wrap(message.payload).int
            if (status == TeslaBleConstants.STATUS_SUCCESS) {
                log("Subscription successful!", LogLevel.INFO)
                _connectionState.update {
                    it.copy(
                        state = SessionState.SUBSCRIBED
                    )
                }
                startPolling()
            } else {
                log("Subscription failed with status: $status", LogLevel.ERROR)
            }
        }

        startPolling()
    }

    private fun startPolling() {
        log("Starting vehicle state polling", LogLevel.INFO)

        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && pairingManager?.isAuthenticated() == true) {
                requestVehicleState()
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        log("Vehicle state polling stopped", LogLevel.INFO)
    }

    private fun requestVehicleState() {
        val subscribeMessage = pairingManager?.createSubscribeMessage()
        if (subscribeMessage != null) {
            try {
                val encrypted = pairingManager?.encryptOutgoingMessage(subscribeMessage)
                if (encrypted != null) {
                    writeCharacteristic?.let { char ->
                        @SuppressLint("MissingPermission")
                        char.setValue(encrypted)
                        bluetoothGatt?.writeCharacteristic(char)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to request vehicle state")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendMessage(message: TeslaBleMessage) {
        val data = message.toBytes()
        log("Sending message: ${TeslaBleConstants.getMessageTypeName(message.messageType)}, size=${data.size}", LogLevel.DEBUG)
        log("Message hex: ${data.toHexString()}", LogLevel.DEBUG)

        readCharacteristic?.let { char ->
            char.setValue(data)
            val success = bluetoothGatt?.writeCharacteristic(char) ?: false
            if (!success) {
                log("Write characteristic failed", LogLevel.ERROR)
            }
        }
    }

    private fun writeCharacteristic?.let(block: (BluetoothGattCharacteristic) -> Unit) {
        this?.let(block)
    }

    @SuppressLint("MissingPermission")
    fun enableNotifications() {
        notifyCharacteristic?.let { char ->
            bluetoothGatt?.setCharacteristicNotification(char, true)

            val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                bluetoothGatt?.writeDescriptor(it)
            }
        }
    }

    private fun log(message: String, level: LogLevel) {
        val entry = BleLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message
        )

        _logEntries.value = (_logEntries.value + entry).takeLast(500)

        when (level) {
            LogLevel.DEBUG -> Timber.tag(TAG).d(message)
            LogLevel.INFO -> Timber.tag(TAG).i(message)
            LogLevel.WARNING -> Timber.tag(TAG).w(message)
            LogLevel.ERROR -> Timber.tag(TAG).e(message)
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    fun release() {
        scope.cancel()
        disconnect()
    }
}
