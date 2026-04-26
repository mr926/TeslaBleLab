package com.teslablelab.domain.crypto

import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.KeyAgreement
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import com.jakewharton.timber.Timber
import kotlin.experimental.xor

class TeslaCrypto {
    companion object {
        private const val TAG = "TeslaCrypto"
        private const val KEY_SIZE = 256
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyPairGenerator = KeyPairGenerator.getInstance("EC")
    private var keyPair: java.security.KeyPair? = null

    init {
        keyPairGenerator.initialize(KEY_SIZE, SecureRandom())
        keyPair = keyPairGenerator.generateKeyPair()
        Timber.tag(TAG).d("EC key pair generated")
    }

    fun getPublicKey(): ByteArray {
        return keyPair?.public?.encoded ?: throw IllegalStateException("Key pair not initialized")
    }

    fun getPrivateKey(): ByteArray {
        return keyPair?.private?.encoded ?: throw IllegalStateException("Key pair not initialized")
    }

    fun getPublicKeyPoint(): ByteArray {
        val publicKey = keyPair?.public as? java.security.interfaces.ECPublicKey
            ?: throw IllegalStateException("Not an EC public key")
        val w = publicKey.w
        val xBytes = unsignedToBytes(w.x)
        val yBytes = unsignedToBytes(w.y)
        return byteArrayOf(0x04) + xBytes + yBytes
    }

    private fun unsignedToBytes(bigInt: java.math.BigInteger): ByteArray {
        val bytes = bigInt.toByteArray()
        return if (bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    fun performEcdh(peerPublicKeyBytes: ByteArray): ByteArray {
        Timber.tag(TAG).d("Performing ECDH key exchange")
        Timber.tag(TAG).d("Our public key: ${getPublicKeyPoint().toHexString()}")

        val peerPublicKey = decodeEcPublicKey(peerPublicKeyBytes)
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(keyPair?.private)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()
        Timber.tag(TAG).d("ECDH shared secret: ${sharedSecret.toHexString()}")
        return sharedSecret
    }

    private fun decodeEcPublicKey(encoded: ByteArray): PublicKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(encoded))
    }

    fun deriveSessionKey(sharedSecret: ByteArray, nonce: ByteArray, isOutgoing: Boolean): ByteArray {
        Timber.tag(TAG).d("Deriving session key")
        Timber.tag(TAG).d("Shared secret: ${sharedSecret.toHexString()}")
        Timber.tag(TAG).d("Nonce: ${nonce.toHexString()}")
        Timber.tag(TAG).d("Is outgoing: $isOutgoing")

        val info = if (isOutgoing) "OUTGOING" else "INCOMING"
        val combined = sharedSecret + nonce + info.toByteArray()

        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val derivedKey = sha256.digest(combined)
        Timber.tag(TAG).d("Derived session key: ${derivedKey.toHexString()}")
        return derivedKey
    }

    fun encryptAesGcm(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): EncryptedData {
        Timber.tag(TAG).d("Encrypting with AES-GCM")
        Timber.tag(TAG).d("Plaintext: ${plaintext.toHexString()}")
        Timber.tag(TAG).d("Key: ${key.toHexString()}")
        Timber.tag(TAG).d("Nonce: ${nonce.toHexString()}")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)
        val encryptedData = EncryptedData(
            ciphertext = ciphertext.copyOfRange(0, ciphertext.size - 16),
            authTag = ciphertext.copyOfRange(ciphertext.size - 16, ciphertext.size)
        )

        Timber.tag(TAG).d("Encrypted: ${encryptedData.ciphertext.toHexString()}")
        Timber.tag(TAG).d("Auth tag: ${encryptedData.authTag.toHexString()}")
        return encryptedData
    }

    fun decryptAesGcm(encryptedData: EncryptedData, key: ByteArray, nonce: ByteArray): ByteArray {
        Timber.tag(TAG).d("Decrypting with AES-GCM")
        Timber.tag(TAG).d("Ciphertext: ${encryptedData.ciphertext.toHexString()}")
        Timber.tag(TAG).d("Auth tag: ${encryptedData.authTag.toHexString()}")
        Timber.tag(TAG).d("Key: ${key.toHexString()}")
        Timber.tag(TAG).d("Nonce: ${nonce.toHexString()}")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val plaintext = cipher.doFinal(encryptedData.ciphertext + encryptedData.authTag)
        Timber.tag(TAG).d("Decrypted: ${plaintext.toHexString()}")
        return plaintext
    }

    fun generateNonce(length: Int = GCM_IV_LENGTH): ByteArray {
        val nonce = ByteArray(length)
        SecureRandom().nextBytes(nonce)
        Timber.tag(TAG).d("Generated nonce: ${nonce.toHexString()}")
        return nonce
    }

    fun sign(data: ByteArray): ByteArray {
        Timber.tag(TAG).d("Signing data: ${data.toHexString()}")
        val signature = if (keyPair?.private != null) {
            val signer = java.security.Signature.getInstance("SHA256withECDSA")
            signer.initSign(keyPair?.private)
            signer.update(data)
            signer.sign()
        } else {
            throw IllegalStateException("Private key not available")
        }
        Timber.tag(TAG).d("Signature: ${signature.toHexString()}")
        return signature
    }

    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val verifier = java.security.Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(decodeEcPublicKey(publicKey))
            verifier.update(data)
            verifier.verify(signature)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Signature verification failed")
            false
        }
    }

    data class EncryptedData(
        val ciphertext: ByteArray,
        val authTag: ByteArray
    )

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
