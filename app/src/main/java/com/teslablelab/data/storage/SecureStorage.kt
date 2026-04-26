package com.teslablelab.data.storage

import android.content.Context
import android.util.Log
import com.teslablelab.toHexString
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorage(context: Context) {
    private val TAG = "SecureStorage"
    private val PREFS_NAME = "tesla_ble_secure_prefs"
    private val KEY_PRIVATE_KEY = "encrypted_private_key"
    private val KEY_PUBLIC_KEY = "public_key"
    private val KEY_VIN = "paired_vin"
    private val KEY_PAIRED_ADDRESS = "paired_device_address"
    private val KEY_SESSION_DATA = "session_data"

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveKeyPair(vin: String, publicKey: ByteArray, encryptedPrivateKey: ByteArray, deviceAddress: String) {
        Log.d(TAG, "Saving key pair for VIN: $vin")

        sharedPreferences.edit()
            .putString(KEY_VIN, vin)
            .putString(KEY_PUBLIC_KEY, publicKey.toHexString())
            .putString(KEY_PRIVATE_KEY, encryptedPrivateKey.toHexString())
            .putString(KEY_PAIRED_ADDRESS, deviceAddress)
            .commit()

        Log.d(TAG, "Key pair saved successfully")
    }

    fun getSavedVin(): String? {
        return sharedPreferences.getString(KEY_VIN, null)
    }

    fun getSavedDeviceAddress(): String? {
        return sharedPreferences.getString(KEY_PAIRED_ADDRESS, null)
    }

    fun getPublicKey(): ByteArray? {
        val hex = sharedPreferences.getString(KEY_PUBLIC_KEY, null) ?: return null
        return hexStringToByteArray(hex)
    }

    fun getEncryptedPrivateKey(): ByteArray? {
        val hex = sharedPreferences.getString(KEY_PRIVATE_KEY, null) ?: return null
        return hexStringToByteArray(hex)
    }

    fun saveSessionData(vin: String, sessionId: Int, sessionToken: Int, sharedSecret: ByteArray) {
        Log.d(TAG, "Saving session data for VIN: $vin")

        val sessionDataJson = "$sessionId|$sessionToken|${sharedSecret.toHexString()}"
        sharedPreferences.edit()
            .putString("${KEY_SESSION_DATA}_$vin", sessionDataJson)
            .apply()
    }

    fun getSessionData(vin: String): Triple<Int, Int, ByteArray>? {
        val sessionDataJson = sharedPreferences.getString("${KEY_SESSION_DATA}_$vin", null) ?: return null

        return try {
            val parts = sessionDataJson.split("|")
            if (parts.size != 3) return null

            val sessionId = parts[0].toInt()
            val sessionToken = parts[1].toInt()
            val sharedSecret = hexStringToByteArray(parts[2])

            Triple(sessionId, sessionToken, sharedSecret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse session data", e)
            null
        }
    }

    fun clearPairingData(vin: String): Boolean {
        Log.d(TAG, "Clearing pairing data for VIN: $vin")

        return sharedPreferences.edit()
            .remove(KEY_VIN)
            .remove(KEY_PUBLIC_KEY)
            .remove(KEY_PRIVATE_KEY)
            .remove(KEY_PAIRED_ADDRESS)
            .remove("${KEY_SESSION_DATA}_$vin")
            .commit()
    }

    fun clearAllPairingData(): Boolean {
        val savedVin = getSavedVin()
        Log.d(TAG, "Clearing all pairing data (savedVin=$savedVin)")

        return sharedPreferences.edit().clear().commit()
    }

    fun hasExistingPairing(): Boolean {
        return getSavedVin() != null && getEncryptedPrivateKey() != null
    }

    fun hasExistingPairing(vin: String): Boolean {
        return getSavedVin() == vin && getEncryptedPrivateKey() != null
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
