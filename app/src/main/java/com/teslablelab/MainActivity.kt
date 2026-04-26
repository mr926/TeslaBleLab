package com.teslablelab.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.teslablelab.R
import com.teslablelab.data.repository.BleConnectionState
import com.teslablelab.domain.protocol.PairingState
import com.teslablelab.domain.protocol.SessionState
import com.teslablelab.domain.protocol.TeslaBleConstants
import com.teslablelab.ui.adapter.DeviceAdapter
import com.teslablelab.ui.adapter.LogAdapter
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.carserver.MediaSourceType
import com.tesla.generated.carserver.MediaPlaybackStatus
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
    private lateinit var deleteKeyButton: MaterialButton
    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var lockStateValue: TextView
    private lateinit var sleepStatusValue: TextView
    private lateinit var userPresenceValue: TextView
    private lateinit var frontDoorsValue: TextView
    private lateinit var rearDoorsValue: TextView
    private lateinit var trunksValue: TextView
    private lateinit var chargePortValue: TextView
    private lateinit var roofValue: TextView
    // Drive
    private lateinit var driveShiftState: TextView
    private lateinit var driveSpeed: TextView
    private lateinit var drivePower: TextView
    private lateinit var driveOdometer: TextView
    private lateinit var driveRoute: TextView
    // Charge
    private lateinit var chargeBatteryLevel: TextView
    private lateinit var chargeState: TextView
    private lateinit var chargeRange: TextView
    private lateinit var chargeRate: TextView
    private lateinit var chargeLimit: TextView
    private lateinit var chargeTimeToFull: TextView
    private lateinit var chargeChargerPower: TextView
    private lateinit var chargeAmps: TextView
    // Climate
    private lateinit var climateInsideTemp: TextView
    private lateinit var climateOutsideTemp: TextView
    private lateinit var climateOn: TextView
    private lateinit var climateFan: TextView
    private lateinit var climateDriverTemp: TextView
    private lateinit var climatePassengerTemp: TextView
    private lateinit var climateSeatHeaters: TextView
    private lateinit var climatePreconditioning: TextView
    // Tires
    private lateinit var tireFL: TextView
    private lateinit var tireFR: TextView
    private lateinit var tireRL: TextView
    private lateinit var tireRR: TextView
    // Media
    private lateinit var mediaNowPlaying: TextView
    private lateinit var mediaVolume: TextView
    private lateinit var mediaPlayback: TextView
    private lateinit var mediaSource: TextView

    private lateinit var logRecyclerView: RecyclerView
    private lateinit var copyLogButton: MaterialButton
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
            Log.d(TAG, "All BLE permissions granted")
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

        vinInput.setText(TeslaBleConstants.DEFAULT_VIN)
        Log.d(TAG, "MainActivity created")
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
        deleteKeyButton = findViewById(R.id.deleteKeyButton)
        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)
        lockStateValue = findViewById(R.id.lockStateValue)
        sleepStatusValue = findViewById(R.id.sleepStatusValue)
        userPresenceValue = findViewById(R.id.userPresenceValue)
        frontDoorsValue = findViewById(R.id.frontDoorsValue)
        rearDoorsValue = findViewById(R.id.rearDoorsValue)
        trunksValue = findViewById(R.id.trunksValue)
        chargePortValue = findViewById(R.id.chargePortValue)
        roofValue = findViewById(R.id.roofValue)
        // Drive
        driveShiftState = findViewById(R.id.driveShiftState)
        driveSpeed = findViewById(R.id.driveSpeed)
        drivePower = findViewById(R.id.drivePower)
        driveOdometer = findViewById(R.id.driveOdometer)
        driveRoute = findViewById(R.id.driveRoute)
        // Charge
        chargeBatteryLevel = findViewById(R.id.chargeBatteryLevel)
        chargeState = findViewById(R.id.chargeState)
        chargeRange = findViewById(R.id.chargeRange)
        chargeRate = findViewById(R.id.chargeRate)
        chargeLimit = findViewById(R.id.chargeLimit)
        chargeTimeToFull = findViewById(R.id.chargeTimeToFull)
        chargeChargerPower = findViewById(R.id.chargeChargerPower)
        chargeAmps = findViewById(R.id.chargeAmps)
        // Climate
        climateInsideTemp = findViewById(R.id.climateInsideTemp)
        climateOutsideTemp = findViewById(R.id.climateOutsideTemp)
        climateOn = findViewById(R.id.climateOn)
        climateFan = findViewById(R.id.climateFan)
        climateDriverTemp = findViewById(R.id.climateDriverTemp)
        climatePassengerTemp = findViewById(R.id.climatePassengerTemp)
        climateSeatHeaters = findViewById(R.id.climateSeatHeaters)
        climatePreconditioning = findViewById(R.id.climatePreconditioning)
        // Tires
        tireFL = findViewById(R.id.tireFL)
        tireFR = findViewById(R.id.tireFR)
        tireRL = findViewById(R.id.tireRL)
        tireRR = findViewById(R.id.tireRR)
        // Media
        mediaNowPlaying = findViewById(R.id.mediaNowPlaying)
        mediaVolume = findViewById(R.id.mediaVolume)
        mediaPlayback = findViewById(R.id.mediaPlayback)
        mediaSource = findViewById(R.id.mediaSource)

        logRecyclerView = findViewById(R.id.logRecyclerView)
        copyLogButton = findViewById(R.id.copyLogButton)
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

        deleteKeyButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Pairing Key")
                .setMessage("This deletes only the local private key stored on this phone. Vehicle-side keys must still be removed from the car screen.")
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.deleteSavedKey()
                    Toast.makeText(this, "Local pairing key deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        copyLogButton.setOnClickListener {
            val entries = viewModel.logEntries.value
            if (entries.isEmpty()) {
                Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            val logText = entries.joinToString("\n") { entry ->
                val time = sdf.format(java.util.Date(entry.timestamp))
                val level = entry.level.name.first()
                "$time $level ${entry.message}"
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TeslaBleLab Log", logText))
            Toast.makeText(this, "Log copied (${entries.size} entries)", Toast.LENGTH_SHORT).show()
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
                        connectButton.isEnabled = devices.isNotEmpty()
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
                    }
                }
            }
        }
    }

    private var lastPairingState: PairingState = PairingState.IDLE

    private fun updateConnectionUI(state: BleConnectionState) {
        if (state.pairingState == PairingState.WAITING_FOR_NFC_TAP && lastPairingState != PairingState.WAITING_FOR_NFC_TAP) {
            showNfcTapDialog()
        }
        lastPairingState = state.pairingState

        val stateText = when (state.state) {
            SessionState.DISCONNECTED -> "Disconnected"
            SessionState.CONNECTING -> "Connecting..."
            SessionState.DISCOVERING_SERVICES -> "Discovering Services..."
            SessionState.REQUESTING_MTU -> "Requesting MTU..."
            SessionState.ESTABLISHING_SESSION -> "Establishing Session..."
            SessionState.AUTHENTICATED -> when (state.pairingState) {
                PairingState.IDLE -> "BLE Session Ready"
                PairingState.SESSION_INFO_REQUESTED -> "Checking Authorization..."
                PairingState.SESSION_ESTABLISHED -> "Vehicle Authorized"
                PairingState.WHITELIST_OPERATION_SENT -> "Pairing Request Sent"
                PairingState.WAITING_FOR_NFC_TAP -> "Tap Card Key Now!"
                PairingState.PAIRED -> "Authorized & Connected"
                PairingState.ERROR -> "Pairing Error"
            }
            SessionState.ERROR -> "Error"
        }
        statusText.text = stateText

        val indicatorColor = when (state.state) {
            SessionState.DISCONNECTED -> R.color.error
            SessionState.CONNECTING,
            SessionState.DISCOVERING_SERVICES,
            SessionState.REQUESTING_MTU,
            SessionState.ESTABLISHING_SESSION -> R.color.warning
            SessionState.AUTHENTICATED -> when (state.pairingState) {
                PairingState.PAIRED -> R.color.success
                PairingState.WAITING_FOR_NFC_TAP -> R.color.warning
                PairingState.ERROR -> R.color.error
                else -> R.color.warning
            }
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
        deleteKeyButton.isEnabled = viewModel.hasSavedKey()
        reconnectButton.isEnabled = state.state == SessionState.DISCONNECTED

        state.vehicleStatus?.let { vs ->
            lockStateValue.text = when (vs.vehicleLockState) {
                VehicleLockState_E.VEHICLELOCKSTATE_LOCKED -> "Locked"
                VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED -> "Unlocked"
                VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED -> "Int.Locked"
                VehicleLockState_E.VEHICLELOCKSTATE_SELECTIVE_UNLOCKED -> "Selective"
                else -> vs.vehicleLockState.name
            }
            sleepStatusValue.text = when (vs.vehicleSleepStatus) {
                VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE -> "Awake"
                VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP -> "Asleep"
                else -> "Unknown"
            }
            userPresenceValue.text = when (vs.userPresence) {
                UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT -> "Present"
                UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT -> "Not Present"
                else -> "Unknown"
            }
        } ?: run {
            lockStateValue.text = "--"
            sleepStatusValue.text = "--"
            userPresenceValue.text = "--"
        }

        state.snapshot.vehicleLockState?.let { lockStateValue.text = snapshotLabel(it) }
        val closureSnapshot = state.snapshot.closureStatuses
        frontDoorsValue.text = snapshotPair(closureSnapshot.frontDriverDoor, closureSnapshot.frontPassengerDoor) ?: "--"
        rearDoorsValue.text = snapshotPair(closureSnapshot.rearDriverDoor, closureSnapshot.rearPassengerDoor) ?: "--"
        trunksValue.text = snapshotPair(closureSnapshot.frontTrunk, closureSnapshot.rearTrunk) ?: "--"
        chargePortValue.text = closureSnapshot.chargePort?.let { snapshotLabel(it) } ?: "--"
        roofValue.text = closureSnapshot.roof?.let { snapshotLabel(it) } ?: "--"

        // --- Drive State ---
        state.driveState?.let { ds ->
            driveShiftState.text = shiftLabel(ds.shiftState.typeCase.name)
            val speedKmh = if (ds.speedFloat > 0) ds.speedFloat else ds.speed.toFloat()
            driveSpeed.text = String.format("%.0f", speedKmh * 1.60934f)
            drivePower.text = "${ds.power}"
            driveOdometer.text = String.format("%.0f", ds.odometerInHundredthsOfAMile / 100.0 * 1.60934)
            val route = ds.activeRouteDestination
            if (route.isNotEmpty()) {
                driveRoute.text = "Nav: $route (${String.format("%.0f", ds.activeRouteMinutesToArrival)} min)"
                driveRoute.visibility = View.VISIBLE
            } else {
                driveRoute.visibility = View.GONE
            }
        } ?: run {
            driveShiftState.text = "--"
            driveSpeed.text = "--"
            drivePower.text = "--"
            driveOdometer.text = "--"
            driveRoute.visibility = View.GONE
        }

        // --- Charge State ---
        state.chargeState?.let { cs ->
            chargeBatteryLevel.text = "${cs.batteryLevel}%"
            chargeState.text = chargingStateLabel(cs.chargingState.typeCase.name)
            chargeRange.text = String.format("%.0f", cs.batteryRange * 1.60934f)
            chargeRate.text = "${cs.chargeRateMph}"
            chargeLimit.text = "${cs.chargeLimitSoc}%"
            chargeTimeToFull.text = if (cs.minutesToFullCharge > 0) "${cs.minutesToFullCharge}" else "--"
            chargeChargerPower.text = if (cs.chargerPower > 0) "${cs.chargerPower}" else "--"
            chargeAmps.text = if (cs.chargingAmps > 0) "${cs.chargingAmps}" else "--"
        } ?: run {
            chargeBatteryLevel.text = "--"
            chargeState.text = "--"
            chargeRange.text = "--"
            chargeRate.text = "--"
            chargeLimit.text = "--"
            chargeTimeToFull.text = "--"
            chargeChargerPower.text = "--"
            chargeAmps.text = "--"
        }

        // --- Climate State ---
        state.climateState?.let { cl ->
            climateInsideTemp.text = String.format("%.1f°C", cl.insideTempCelsius)
            climateOutsideTemp.text = String.format("%.1f°C", cl.outsideTempCelsius)
            climateOn.text = if (cl.isClimateOn) "ON" else "OFF"
            climateFan.text = "${cl.fanStatus}"
            climateDriverTemp.text = String.format("%.1f°C", cl.driverTempSetting)
            climatePassengerTemp.text = String.format("%.1f°C", cl.passengerTempSetting)
            val heaters = mutableListOf<String>()
            if (cl.seatHeaterLeft > 0) heaters.add("L:${cl.seatHeaterLeft}")
            if (cl.seatHeaterRight > 0) heaters.add("R:${cl.seatHeaterRight}")
            if (cl.steeringWheelHeater) heaters.add("Wheel")
            climateSeatHeaters.text = if (heaters.isNotEmpty()) heaters.joinToString(" ") else "Off"
            climatePreconditioning.text = if (cl.isPreconditioning) "Yes" else "No"
        } ?: run {
            climateInsideTemp.text = "--"
            climateOutsideTemp.text = "--"
            climateOn.text = "--"
            climateFan.text = "--"
            climateDriverTemp.text = "--"
            climatePassengerTemp.text = "--"
            climateSeatHeaters.text = "--"
            climatePreconditioning.text = "--"
        }

        // --- Tire Pressure ---
        state.tirePressureState?.let { tp ->
            tireFL.text = formatTirePressure(tp.tpmsPressureFl, tp.tpmsHardWarningFl)
            tireFR.text = formatTirePressure(tp.tpmsPressureFr, tp.tpmsHardWarningFr)
            tireRL.text = formatTirePressure(tp.tpmsPressureRl, tp.tpmsHardWarningRl)
            tireRR.text = formatTirePressure(tp.tpmsPressureRr, tp.tpmsHardWarningRr)
        } ?: run {
            tireFL.text = "--"
            tireFR.text = "--"
            tireRL.text = "--"
            tireRR.text = "--"
        }

        // --- Media State ---
        state.mediaState?.let { ms ->
            val artist = ms.nowPlayingArtist
            val title = ms.nowPlayingTitle
            if (artist.isNotEmpty() || title.isNotEmpty()) {
                mediaNowPlaying.text = "$artist - $title"
            } else {
                mediaNowPlaying.text = "No media"
            }
            val vol = ms.audioVolume
            val volMax = ms.audioVolumeMax
            mediaVolume.text = if (volMax > 0) String.format("%.0f/%.0f", vol, volMax) else String.format("%.0f", vol)
            mediaPlayback.text = when (ms.mediaPlaybackStatus) {
                MediaPlaybackStatus.Playing -> "Playing"
                MediaPlaybackStatus.Paused -> "Paused"
                MediaPlaybackStatus.Stopped -> "Stopped"
                else -> "--"
            }
            mediaSource.text = when (ms.nowPlayingSource) {
                MediaSourceType.MediaSourceType_Bluetooth -> "Bluetooth"
                MediaSourceType.MediaSourceType_Spotify -> "Spotify"
                MediaSourceType.MediaSourceType_TuneIn -> "TuneIn"
                MediaSourceType.MediaSourceType_Tidal -> "Tidal"
                MediaSourceType.MediaSourceType_FM -> "FM"
                MediaSourceType.MediaSourceType_AM -> "AM"
                MediaSourceType.MediaSourceType_Slacker -> "Slacker"
                MediaSourceType.MediaSourceType_LocalFiles -> "USB"
                else -> ms.nowPlayingSource.name
            }
        } ?: run {
            mediaNowPlaying.text = "No media"
            mediaVolume.text = "--"
            mediaPlayback.text = "--"
            mediaSource.text = "--"
        }
    }

    private fun formatTirePressure(psi: Float, hasWarning: Boolean): String {
        return if (hasWarning) {
            String.format("%.1f !", psi)
        } else {
            String.format("%.1f", psi)
        }
    }

    private fun shiftLabel(typeName: String): String = when (typeName) {
        "P" -> "P"
        "R" -> "R"
        "N" -> "N"
        "D" -> "D"
        "SNA" -> "SNA"
        else -> "--"
    }

    private fun chargingStateLabel(typeName: String): String = when (typeName) {
        "Charging" -> "Charging"
        "Complete" -> "Complete"
        "Disconnected" -> "Disconnected"
        "Stopped" -> "Stopped"
        "Starting" -> "Starting"
        "NoPower" -> "No Power"
        "Calibrating" -> "Calibrating"
        else -> "--"
    }

    private fun showNfcTapDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔑 刷卡片钥匙")
            .setMessage("请在车辆 B 柱的 NFC 感应区域刷一下你的卡片钥匙，然后在车机屏幕上点击「配对」。")
            .setPositiveButton("知道了", null)
            .setCancelable(false)
            .show()
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

    private fun closureLabel(state: ClosureState_E): String = when (state) {
        ClosureState_E.CLOSURESTATE_CLOSED -> "Closed"
        ClosureState_E.CLOSURESTATE_OPEN -> "Open"
        ClosureState_E.CLOSURESTATE_AJAR -> "Ajar"
        ClosureState_E.CLOSURESTATE_OPENING -> "Opening"
        ClosureState_E.CLOSURESTATE_CLOSING -> "Closing"
        ClosureState_E.CLOSURESTATE_FAILED_UNLATCH -> "Fail"
        else -> "?"
    }

    private fun closurePair(left: ClosureState_E, right: ClosureState_E): String {
        return "${closureLabel(left)} / ${closureLabel(right)}"
    }

    private fun snapshotLabel(value: String): String {
        return value.split("_").joinToString(" ") { part ->
            part.replaceFirstChar { c -> c.uppercase() }
        }
    }

    private fun snapshotPair(left: String?, right: String?): String? {
        if (left == null && right == null) return null
        val leftText = left?.let { snapshotLabel(it) } ?: "--"
        val rightText = right?.let { snapshotLabel(it) } ?: "--"
        return "$leftText / $rightText"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) {
            viewModel.stopScan()
        }
    }
}
