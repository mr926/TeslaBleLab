package com.teslablelab.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teslablelab.data.repository.BleConnectionState
import com.teslablelab.data.repository.BleLogEntry
import com.teslablelab.data.repository.LogLevel
import com.teslablelab.data.repository.TeslaBleRepository
import com.teslablelab.data.storage.SecureStorage
import com.teslablelab.domain.protocol.PairingState
import com.teslablelab.domain.protocol.SessionState
import com.teslablelab.domain.protocol.TeslaBleConstants
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"

    private val repository = TeslaBleRepository(application.applicationContext)
    private val secureStorage = SecureStorage(application.applicationContext)

    val connectionState: StateFlow<BleConnectionState> = repository.connectionState
    val logEntries: StateFlow<List<BleLogEntry>> = repository.logEntries
    val scannedDevices: StateFlow<List<BluetoothDevice>> = repository.scannedDevices

    private val _vinInput = MutableStateFlow(TeslaBleConstants.DEFAULT_VIN)
    val vinInput: StateFlow<String> = _vinInput.asStateFlow()

    init {
        Log.d(TAG, "MainViewModel initialized, VIN: ${_vinInput.value}")
    }

    fun updateVinInput(vin: String) {
        _vinInput.value = vin.uppercase().take(17)
    }

    fun startScan() {
        repository.startScan(_vinInput.value)
    }

    fun stopScan() {
        repository.stopScan()
    }

    fun connectToDevice(device: BluetoothDevice) {
        repository.connectToDevice(device, _vinInput.value)
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun clearPairing() {
        repository.clearPairing()
    }

    fun hasSavedKey(): Boolean = repository.hasSavedKey()

    fun deleteSavedKey() {
        repository.deleteSavedKey()
    }

    fun reconnect() {
        Log.d(TAG, "Reconnect not yet implemented")
    }

    fun clearLogs() {
        Log.d(TAG, "Clearing log entries")
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()
        Log.d(TAG, "MainViewModel cleared")
    }
}
