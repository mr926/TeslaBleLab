package com.teslablelab.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.jakewharton.timber.Timber
import com.teslablelab.R
import com.teslablelab.data.repository.BleConnectionState
import com.teslablelab.data.repository.SessionState
import com.teslablelab.ui.adapter.DeviceAdapter
import com.teslablelab.ui.adapter.LogAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    private val viewModel: MainViewModel by viewModels()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var deviceInfo: TextView
    private lateinit var errorText: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var vinInput: TextInputEditText
    private lateinit var scanButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var speedValue: TextView
    private lateinit var powerValue: TextView
    private lateinit var shiftStateValue: TextView
    private lateinit var odometerValue: TextView
    private lateinit var batteryLevelValue: TextView
    private lateinit var logRecyclerView: RecyclerView
    private lateinit var clearLogButton: MaterialButton
    private lateinit var reconnectButton: MaterialButton
    private lateinit var clearPairingButton: MaterialButton

    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var logAdapter: LogAdapter

    private var isScanning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Timber.tag(TAG).d("All BLE permissions granted")
            viewModel.startScan()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerViews()
        setupListeners()
        observeState()

        Timber.tag(TAG).d("MainActivity created")
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        deviceInfo = findViewById(R.id.deviceInfo)
        errorText = findViewById(R.id.errorText)
        lastUpdateText = findViewById(R.id.lastUpdateText)
        vinInput = findViewById(R.id.vinInput)
        scanButton = findViewById(R.id.scanButton)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)
        speedValue = findViewById(R.id.speedValue)
        powerValue = findViewById(R.id.powerValue)
        shiftStateValue = findViewById(R.id.shiftStateValue)
        odometerValue = findViewById(R.id.odometerValue)
        batteryLevelValue = findViewById(R.id.batteryLevelValue)
        logRecyclerView = findViewById(R.id.logRecyclerView)
        clearLogButton = findViewById(R.id.clearLogButton)
        reconnectButton = findViewById(R.id.reconnectButton)
        clearPairingButton = findViewById(R.id.clearPairingButton)
    }

    private fun setupRecyclerViews() {
        deviceAdapter = DeviceAdapter { device ->
            viewModel.stopScan()
            isScanning = false
            scanButton.text = "Scan"
            viewModel.connectToDevice(device)
        }
        devicesRecyclerView.layoutManager = LinearLayoutManager(this)
        devicesRecyclerView.adapter = deviceAdapter

        logAdapter = LogAdapter()
        logRecyclerView.layoutManager = LinearLayoutManager(this)
        logRecyclerView.adapter = logAdapter
    }

    private fun setupListeners() {
        vinInput.doAfterTextChanged { text ->
            viewModel.updateVinInput(text?.toString() ?: "")
        }

        scanButton.setOnClickListener {
            if (isScanning) {
                viewModel.stopScan()
                isScanning = false
                scanButton.text = "Scan"
            } else {
                if (checkBlePermissions()) {
                    viewModel.startScan()
                    isScanning = true
                    scanButton.text = "Stop"
                }
            }
        }

        connectButton.setOnClickListener {
            val devices = viewModel.scannedDevices.value
            if (devices.isNotEmpty()) {
                viewModel.connectToDevice(devices.first())
            }
        }

        disconnectButton.setOnClickListener {
            viewModel.disconnect()
        }

        clearLogButton.setOnClickListener {
            viewModel.clearLogs()
        }

        reconnectButton.setOnClickListener {
            viewModel.reconnect()
        }

        clearPairingButton.setOnClickListener {
            viewModel.clearPairing()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collectLatest { state ->
                        updateConnectionUI(state)
                    }
                }

                launch {
                    viewModel.scannedDevices.collectLatest { devices ->
                        deviceAdapter.submitList(devices)
                        connectButton.isEnabled = devices.isNotEmpty() && viewModel.vinInput.value.length == 17
                    }
                }

                launch {
                    viewModel.logEntries.collectLatest { entries ->
                        logAdapter.submitList(entries) {
                            if (entries.isNotEmpty()) {
                                logRecyclerView.scrollToPosition(entries.size - 1)
                            }
                        }
                    }
                }

                launch {
                    viewModel.vinInput.collectLatest { vin ->
                        if (vinInput.text?.toString() != vin) {
                            vinInput.setText(vin)
                        }
                        connectButton.isEnabled = vin.length == 17 && viewModel.scannedDevices.value.isNotEmpty()
                    }
                }
            }
        }
    }

    private fun updateConnectionUI(state: BleConnectionState) {
        val stateText = when (state.state) {
            SessionState.DISCONNECTED -> "Disconnected"
            SessionState.CONNECTING -> "Connecting..."
            SessionState.DISCOVERING_SERVICES -> "Discovering Services..."
            SessionState.REQUESTING_MTU -> "Requesting MTU..."
            SessionState.PAIRING -> "Pairing..."
            SessionState.AUTHENTICATED -> "Authenticated"
            SessionState.SUBSCRIBED -> "Connected"
            SessionState.ERROR -> "Error"
        }
        statusText.text = stateText

        val indicatorColor = when (state.state) {
            SessionState.DISCONNECTED -> R.color.error
            SessionState.CONNECTING,
            SessionState.DISCOVERING_SERVICES,
            SessionState.REQUESTING_MTU,
            SessionState.PAIRING -> R.color.warning
            SessionState.AUTHENTICATED,
            SessionState.SUBSCRIBED -> R.color.success
            SessionState.ERROR -> R.color.error
        }

        (statusIndicator.background as? GradientDrawable)?.setColor(
            ContextCompat.getColor(this, indicatorColor)
        ) ?: run {
            statusIndicator.setBackgroundColor(ContextCompat.getColor(this, indicatorColor))
        }

        if (state.deviceAddress.isNotEmpty()) {
            deviceInfo.text = "${state.deviceName} (${state.deviceAddress})"
            deviceInfo.visibility = View.VISIBLE
        } else {
            deviceInfo.visibility = View.GONE
        }

        if (state.errorMessage != null) {
            errorText.text = state.errorMessage
            errorText.visibility = View.VISIBLE
        } else {
            errorText.visibility = View.GONE
        }

        if (state.lastUpdateTime > 0) {
            lastUpdateText.text = "Last update: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(state.lastUpdateTime))}"
            lastUpdateText.visibility = View.VISIBLE
        } else {
            lastUpdateText.visibility = View.GONE
        }

        disconnectButton.isEnabled = state.state != SessionState.DISCONNECTED
        reconnectButton.isEnabled = state.state == SessionState.DISCONNECTED && viewModel.vinInput.value.length == 17

        state.vehicleState?.let { vehicle ->
            speedValue.text = if (vehicle.speedFloat > 0) "%.1f mph".format(vehicle.speedFloat) else "--"
            powerValue.text = "${vehicle.power} kW"
            shiftStateValue.text = vehicle.shiftState.ifEmpty { "--" }
            odometerValue.text = "%.1f mi".format(vehicle.odometer)
            batteryLevelValue.text = "${vehicle.valBatteryLevel}%"
        } ?: run {
            speedValue.text = "--"
            powerValue.text = "--"
            shiftStateValue.text = "--"
            odometerValue.text = "--"
            batteryLevelValue.text = "--"
        }
    }

    private fun checkBlePermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            permissionLauncher.launch(permissions)
            return false
        }

        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) {
            viewModel.stopScan()
        }
    }
}
