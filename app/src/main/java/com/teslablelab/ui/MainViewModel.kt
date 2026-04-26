package com.teslablelab.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jakewharton.timber.Timber
import com.teslablelab.data.repository.BleConnectionState
import com.teslablelab.data.repository.BleLogEntry
import com.teslablelab.data.repository.LogLevel
import com.teslablelab.data.repository.TeslaBleRepository
import com.teslablelab.data.storage.SecureStorage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"

    private val repository = TeslaBleRepository(application.applicationContext)
    private val secureStorage = SecureStorage(application.applicationContext)

    val connectionState: StateFlow<BleConnectionState> = repository.connectionState
    val logEntries: StateFlow<List<BleLogEntry>> = repository.logEntries
    val scannedDevices: StateFlow<List<BluetoothDevice>> = repository.scannedDevices

    private val _vinInput = MutableStateFlow("")
    val vinInput: StateFlow<String> = _vinInput.asStateFlow()

    private val _refreshInterval = MutableStateFlow(1000L)
    val refreshInterval: StateFlow<Long> = _refreshInterval.asStateFlow()

    init {
        Timber.tag(TAG).d("MainViewModel initialized")
        secureStorage.getSavedVin()?.let { savedVin ->
            _vinInput.value = savedVin
        }
    }

    fun updateVinInput(vin: String) {
        _vinInput.value = vin.uppercase().take(17)
    }

    fun startScan() {
        repository.startScan()
    }

    fun stopScan() {
        repository.stopScan()
    }

    fun connectToDevice(device: BluetoothDevice) {
        val vin = _vinInput.value
        if (vin.length != 17) {
            Timber.tag(TAG).e("Invalid VIN: $vin")
            return
        }
        repository.connectToDevice(device, vin)
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun clearPairing() {
        val vin = _vinInput.value
        if (vin.isNotEmpty()) {
            secureStorage.clearPairingData(vin)
        }
        repository.clearPairing()
    }

    fun reconnect() {
        val savedAddress = secureStorage.getSavedDeviceAddress()
        val vin = _vinInput.value

        if (savedAddress != null && vin.isNotEmpty()) {
            Timber.tag(TAG).d("Attempting to reconnect to $savedAddress")
        }
    }

    fun clearLogs() {
        Timber.tag(TAG).d("Clearing log entries")
    }

    fun updateRefreshInterval(interval: Long) {
        _refreshInterval.value = interval
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()
        Timber.tag(TAG).d("MainViewModel cleared")
    }
}
