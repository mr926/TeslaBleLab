package com.teslablelab.domain.model

import com.tesla.generated.carserver.ChargeState
import com.tesla.generated.carserver.ClimateState
import com.tesla.generated.carserver.ClosuresState as CarClosuresState
import com.tesla.generated.carserver.DriveState
import com.tesla.generated.carserver.MediaSourceType
import com.tesla.generated.carserver.MediaState
import com.tesla.generated.carserver.TirePressureState
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleStatus

data class TeslaVehicleSnapshot(
    val vehicleLockState: String? = null,
    val closureStatuses: ClosureStatusesSnapshot = ClosureStatusesSnapshot(),
    val driveState: DriveStateSnapshot? = null,
    val chargeState: ChargeStateSnapshot? = null,
    val climateState: ClimateStateSnapshot? = null,
    val tirePressureState: TirePressureSnapshot? = null,
    val mediaState: MediaStateSnapshot? = null
) {
    companion object {
        val EMPTY = TeslaVehicleSnapshot()

        fun from(
            vehicleStatus: VehicleStatus?,
            driveState: DriveState?,
            chargeState: ChargeState?,
            climateState: ClimateState?,
            carClosuresState: CarClosuresState?,
            tirePressureState: TirePressureState?,
            mediaState: MediaState?
        ): TeslaVehicleSnapshot {
            return TeslaVehicleSnapshot(
                vehicleLockState = vehicleStatus?.vehicleLockState?.toLockStateLabel()
                    ?: carClosuresState?.let { carLockLabel(it) },
                closureStatuses = ClosureStatusesSnapshot.from(vehicleStatus, carClosuresState, chargeState),
                driveState = driveState?.toSnapshot(),
                chargeState = chargeState?.toSnapshot(),
                climateState = climateState?.toSnapshot(),
                tirePressureState = tirePressureState?.toSnapshot(),
                mediaState = mediaState?.toSnapshot()
            )
        }
    }
}

data class ClosureStatusesSnapshot(
    val frontDriverDoor: String? = null,
    val frontPassengerDoor: String? = null,
    val rearDriverDoor: String? = null,
    val rearPassengerDoor: String? = null,
    val frontTrunk: String? = null,
    val rearTrunk: String? = null,
    val chargePort: String? = null,
    val roof: String? = null
) {
    companion object {
        fun from(
            vehicleStatus: VehicleStatus?,
            carClosuresState: CarClosuresState?,
            chargeState: ChargeState?
        ): ClosureStatusesSnapshot {
            val vcsec = vehicleStatus?.takeIf { it.hasClosureStatuses() }?.closureStatuses
            val carSunRoof = carClosuresState
                ?.takeIf { it.hasSunRoofState() }
                ?.sunRoofState
                ?.typeCase
                ?.name
                ?.takeIf { it != "TYPE_NOT_SET" && it != "UNKNOWN" }
            val carTonneau = carClosuresState
                ?.takeIf { it.hasTonneauState() }
                ?.tonneauState
                ?.toClosureLabel()
            return ClosureStatusesSnapshot(
                frontDriverDoor = vcsec?.frontDriverDoor?.toClosureLabel()
                    ?: carClosuresState?.let { carOpenClosedLabel(it.hasDoorOpenDriverFront(), it.getDoorOpenDriverFront()) },
                frontPassengerDoor = vcsec?.frontPassengerDoor?.toClosureLabel()
                    ?: carClosuresState?.let { carOpenClosedLabel(it.hasDoorOpenPassengerFront(), it.getDoorOpenPassengerFront()) },
                rearDriverDoor = vcsec?.rearDriverDoor?.toClosureLabel()
                    ?: carClosuresState?.let { carOpenClosedLabel(it.hasDoorOpenDriverRear(), it.getDoorOpenDriverRear()) },
                rearPassengerDoor = vcsec?.rearPassengerDoor?.toClosureLabel()
                    ?: carClosuresState?.let { carOpenClosedLabel(it.hasDoorOpenPassengerRear(), it.getDoorOpenPassengerRear()) },
                frontTrunk = vcsec?.frontTrunk?.toClosureLabel()
                    ?: carClosuresState?.let { carOpenClosedLabel(it.hasDoorOpenTrunkFront(), it.getDoorOpenTrunkFront()) },
                rearTrunk = vcsec?.rearTrunk?.toClosureLabel()
                    ?: carClosuresState?.let { carOpenClosedLabel(it.hasDoorOpenTrunkRear(), it.getDoorOpenTrunkRear()) },
                chargePort = vcsec?.chargePort?.toClosureLabel()
                    ?: chargeState?.let { carOpenClosedLabel(it.hasChargePortDoorOpen(), it.getChargePortDoorOpen()) },
                roof = vcsec?.tonneau?.toClosureLabel() ?: carTonneau ?: carSunRoof?.prettyOneofName()
            )
        }
    }
}

data class DriveStateSnapshot(
    val shiftState: String?,
    val speedMph: Float?,
    val speedKmh: Float?,
    val powerKw: Int?,
    val odometerKm: Double?,
    val navigation: NavigationSnapshot?
)

data class NavigationSnapshot(
    val destination: String,
    val minutesToArrival: Float?,
    val milesToArrival: Float?,
    val trafficMinutesDelay: Float?,
    val energyAtArrival: Float?
)

data class ChargeStateSnapshot(
    val batteryLevel: Int?,
    val chargingState: String?,
    val batteryRangeMiles: Float?,
    val chargeRateMph: Int?,
    val chargeLimitSoc: Int?
)

data class ClimateStateSnapshot(
    val insideTempCelsius: Float?,
    val outsideTempCelsius: Float?,
    val isClimateOn: Boolean?,
    val fanStatus: Int?,
    val driverTempSettingCelsius: Float?,
    val passengerTempSettingCelsius: Float?,
    val seatHeaters: SeatHeaterSnapshot
)

data class SeatHeaterSnapshot(
    val frontLeft: Int?,
    val frontRight: Int?,
    val rearLeft: Int?,
    val rearCenter: Int?,
    val rearRight: Int?,
    val steeringWheel: Boolean?
)

data class TirePressureSnapshot(
    val frontLeftBar: Float?,
    val frontRightBar: Float?,
    val rearLeftBar: Float?,
    val rearRightBar: Float?
)

data class MediaStateSnapshot(
    val artist: String?,
    val title: String?,
    val playbackStatus: String?,
    val source: String?,
    val audioVolume: Float?,
    val audioVolumeMax: Float?
)

private fun DriveState.toSnapshot(): DriveStateSnapshot {
    val speed = if (speedFloat > 0f) speedFloat else speed.toFloat()
    val destination = activeRouteDestination.takeIf { it.isNotBlank() }
    return DriveStateSnapshot(
        shiftState = shiftState.typeCase.name.toShiftLabel(),
        speedMph = speed.takeIf { it > 0f },
        speedKmh = speed.takeIf { it > 0f }?.let { it * 1.60934f },
        powerKw = power.takeIf { it != 0 },
        odometerKm = odometerInHundredthsOfAMile
            .takeIf { it > 0 }
            ?.let { it / 100.0 * 1.60934 },
        navigation = destination?.let {
            NavigationSnapshot(
                destination = it,
                minutesToArrival = activeRouteMinutesToArrival.takeIf { value -> value > 0f },
                milesToArrival = activeRouteMilesToArrival.takeIf { value -> value > 0f },
                trafficMinutesDelay = activeRouteTrafficMinutesDelay.takeIf { value -> value > 0f },
                energyAtArrival = activeRouteEnergyAtArrival
            )
        }
    )
}

private fun ChargeState.toSnapshot(): ChargeStateSnapshot {
    return ChargeStateSnapshot(
        batteryLevel = batteryLevel.takeIf { it > 0 },
        chargingState = chargingState.typeCase.name.prettyOneofName(),
        batteryRangeMiles = batteryRange.takeIf { it > 0f },
        chargeRateMph = chargeRateMph.takeIf { it > 0 },
        chargeLimitSoc = chargeLimitSoc.takeIf { it > 0 }
    )
}

private fun ClimateState.toSnapshot(): ClimateStateSnapshot {
    return ClimateStateSnapshot(
        insideTempCelsius = insideTempCelsius,
        outsideTempCelsius = outsideTempCelsius,
        isClimateOn = isClimateOn,
        fanStatus = fanStatus,
        driverTempSettingCelsius = driverTempSetting,
        passengerTempSettingCelsius = passengerTempSetting,
        seatHeaters = SeatHeaterSnapshot(
            frontLeft = seatHeaterLeft.takeIf { it > 0 },
            frontRight = seatHeaterRight.takeIf { it > 0 },
            rearLeft = seatHeaterRearLeft.takeIf { it > 0 },
            rearCenter = seatHeaterRearCenter.takeIf { it > 0 },
            rearRight = seatHeaterRearRight.takeIf { it > 0 },
            steeringWheel = steeringWheelHeater
        )
    )
}

private fun TirePressureState.toSnapshot(): TirePressureSnapshot {
    return TirePressureSnapshot(
        frontLeftBar = tpmsPressureFl.takeIf { it > 0f },
        frontRightBar = tpmsPressureFr.takeIf { it > 0f },
        rearLeftBar = tpmsPressureRl.takeIf { it > 0f },
        rearRightBar = tpmsPressureRr.takeIf { it > 0f }
    )
}

private fun MediaState.toSnapshot(): MediaStateSnapshot {
    return MediaStateSnapshot(
        artist = nowPlayingArtist.takeIf { it.isNotBlank() },
        title = nowPlayingTitle.takeIf { it.isNotBlank() },
        playbackStatus = mediaPlaybackStatus.name,
        source = nowPlayingSource.toSourceLabel(),
        audioVolume = audioVolume,
        audioVolumeMax = audioVolumeMax.takeIf { it > 0f }
    )
}

private fun carOpenClosedLabel(hasValue: Boolean, isOpen: Boolean): String? =
    if (hasValue) isOpen.toOpenClosedLabel() else null

private fun carLockLabel(closuresState: CarClosuresState): String? =
    if (closuresState.hasLocked()) closuresState.getLocked().toLockedLabel() else null

private fun Boolean.toOpenClosedLabel(): String = if (this) "open" else "closed"

private fun Boolean.toLockedLabel(): String = if (this) "locked" else "unlocked"

private fun VehicleLockState_E.toLockStateLabel(): String = when (this) {
    VehicleLockState_E.VEHICLELOCKSTATE_LOCKED -> "locked"
    VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED -> "unlocked"
    VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED -> "internal_locked"
    VehicleLockState_E.VEHICLELOCKSTATE_SELECTIVE_UNLOCKED -> "selective_unlocked"
    else -> name.lowercase()
}

private fun ClosureState_E.toClosureLabel(): String = when (this) {
    ClosureState_E.CLOSURESTATE_CLOSED -> "closed"
    ClosureState_E.CLOSURESTATE_OPEN -> "open"
    ClosureState_E.CLOSURESTATE_AJAR -> "ajar"
    ClosureState_E.CLOSURESTATE_FAILED_UNLATCH -> "failed_unlatch"
    ClosureState_E.CLOSURESTATE_OPENING -> "opening"
    ClosureState_E.CLOSURESTATE_CLOSING -> "closing"
    ClosureState_E.CLOSURESTATE_UNKNOWN -> "unknown"
    else -> name.lowercase()
}

private fun String.toShiftLabel(): String? = when (this) {
    "P", "R", "N", "D", "SNA" -> this
    else -> null
}

private fun String.prettyOneofName(): String? {
    if (this == "TYPE_NOT_SET" || this == "UNKNOWN") return null
    return replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
}

private fun MediaSourceType.toSourceLabel(): String = when (this) {
    MediaSourceType.MediaSourceType_Bluetooth -> "bluetooth"
    MediaSourceType.MediaSourceType_Spotify -> "spotify"
    MediaSourceType.MediaSourceType_TuneIn -> "tune_in"
    MediaSourceType.MediaSourceType_Tidal -> "tidal"
    MediaSourceType.MediaSourceType_FM -> "fm"
    MediaSourceType.MediaSourceType_AM -> "am"
    MediaSourceType.MediaSourceType_Slacker -> "slacker"
    MediaSourceType.MediaSourceType_LocalFiles -> "local_files"
    MediaSourceType.MediaSourceType_QQMusic,
    MediaSourceType.MediaSourceType_QQMusic2 -> "qq_music"
    MediaSourceType.MediaSourceType_Ximalaya -> "ximalaya"
    MediaSourceType.MediaSourceType_NetEaseMusic -> "netease_music"
    else -> name.removePrefix("MediaSourceType_").lowercase()
}
