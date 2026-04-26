package com.teslablelab.domain.protocol

import android.os.SystemClock
import com.tesla.generated.universalmessage.Domain

data class DomainSession(
    val domain: Domain,
    var sessionKey: ByteArray? = null,
    var sessionCounter: Int = 0,
    var sessionHandle: Int = 0,
    var sessionEpoch: ByteArray = ByteArray(0),
    var vehiclePublicKey: ByteArray = ByteArray(0),
    var sessionClockTime: Long = 0,
    var sessionTimeZeroRealtimeMs: Long = 0,
    var isAuthenticated: Boolean = false
) {
    fun hasSessionKey(): Boolean = sessionKey != null

    fun updateClock(clockTimeSeconds: Long) {
        sessionClockTime = clockTimeSeconds
        sessionTimeZeroRealtimeMs = SystemClock.elapsedRealtime() - clockTimeSeconds * 1000L
    }

    fun currentClockTimeSeconds(): Long {
        if (sessionTimeZeroRealtimeMs == 0L) return sessionClockTime
        val elapsedSeconds = (SystemClock.elapsedRealtime() - sessionTimeZeroRealtimeMs) / 1000L
        return elapsedSeconds.coerceAtLeast(sessionClockTime)
    }

    fun reset() {
        sessionKey = null
        sessionCounter = 0
        sessionHandle = 0
        sessionEpoch = ByteArray(0)
        vehiclePublicKey = ByteArray(0)
        sessionClockTime = 0
        sessionTimeZeroRealtimeMs = 0
        isAuthenticated = false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DomainSession) return false
        return domain == other.domain
    }

    override fun hashCode(): Int = domain.hashCode()
}
