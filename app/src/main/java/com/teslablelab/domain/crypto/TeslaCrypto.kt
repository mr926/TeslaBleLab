package com.teslablelab.domain.crypto

import android.util.Log
import com.teslablelab.toHexString
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECCurve
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TeslaCrypto {
    companion object {
        private const val TAG = "TeslaCrypto"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val SHARED_KEY_SIZE = 16
        private const val GCM_TAG_BYTES = 16
    }

    private val secureRandom = SecureRandom()

    private val p256curve: ECCurve by lazy {
        org.bouncycastle.math.ec.custom.sec.SecP256R1Curve()
    }

    private val p256n: BigInteger by lazy { p256curve.order }
    private val p256domain: ECDomainParameters by lazy {
        val gHex = "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
        val gBytes = ByteArray(gHex.length / 2) { i -> ((gHex.substring(i * 2, i * 2 + 2).toInt(16)).toByte()) }
        ECDomainParameters(p256curve, p256curve.decodePoint(gBytes), p256curve.order, p256curve.cofactor)
    }
    private val p256g: ECPoint by lazy { p256domain.g }

    private var keyPair: org.bouncycastle.crypto.AsymmetricCipherKeyPair? = null

    init {
        generateKeyPair()
    }

    fun generateKeyPair() {
        val keyGen = ECKeyPairGenerator()
        val keyGenParams = ECKeyGenerationParameters(p256domain, secureRandom)
        keyGen.init(keyGenParams)
        keyPair = keyGen.generateKeyPair()
        Log.d(TAG, "EC P-256 key pair generated")
    }

    fun loadPrivateKey(privateKeyBytes: ByteArray): Boolean {
        return try {
            val d = BigInteger(1, privateKeyBytes)
            val publicKeyPoint = p256g.multiply(d)
            val pubKey = ECPublicKeyParameters(publicKeyPoint, p256domain)
            val privKey = ECPrivateKeyParameters(d, p256domain)
            keyPair = org.bouncycastle.crypto.AsymmetricCipherKeyPair(pubKey, privKey)
            Log.d(TAG, "Loaded existing private key, pubkey: ${getPublicKeyPoint().toHexString()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load private key", e)
            false
        }
    }

    fun getPublicKeyPoint(): ByteArray {
        val publicKey = (keyPair?.public as? ECPublicKeyParameters)?.q
            ?: throw IllegalStateException("Key pair not initialized")
        val encoded = publicKey.getEncoded(false)
        Log.d(TAG, "Public key point: ${encoded.toHexString()}")
        return encoded
    }

    fun getPrivateKeyEncoded(): ByteArray {
        val privateKey = (keyPair?.private as? ECPrivateKeyParameters)?.d
            ?: throw IllegalStateException("Key pair not initialized")
        return bigIntToBytes(privateKey, 32)
    }

    fun performECDH(peerPublicKeyBytes: ByteArray): ByteArray {
        Log.d(TAG, "Performing ECDH key exchange")
        Log.d(TAG, "Our public key: ${getPublicKeyPoint().toHexString()}")
        Log.d(TAG, "Peer public key: ${peerPublicKeyBytes.toHexString()}")

        val peerPoint = p256curve.decodePoint(peerPublicKeyBytes)
        val privateKey = keyPair?.private as? ECPrivateKeyParameters
            ?: throw IllegalStateException("Private key not available")

        val sharedPoint = peerPoint.multiply(privateKey.d).normalize()
        val sharedX = sharedPoint.affineXCoord.encoded

        Log.d(TAG, "ECDH shared X: ${sharedX.toHexString()}")

        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest(sharedX)
        val sessionKey = digest.copyOfRange(0, SHARED_KEY_SIZE)
        Log.d(TAG, "Session key (SHA1[:16]): ${sessionKey.toHexString()}")

        return sessionKey
    }

    fun encryptAesGcm(plaintext: ByteArray, key: ByteArray, aad: ByteArray? = null): EncryptedData {
        Log.d(TAG, "Encrypting with AES-128-GCM, plaintext size: ${plaintext.size}")
        Log.d(TAG, "Key: ${key.toHexString()}")
        if (aad != null) {
            Log.d(TAG, "AAD: ${aad.toHexString()}")
        }

        val nonce = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        if (aad != null) {
            cipher.updateAAD(aad)
        }

        val ciphertextWithTag = cipher.doFinal(plaintext)
        val ciphertext = ciphertextWithTag.copyOfRange(0, plaintext.size)
        val tag = ciphertextWithTag.copyOfRange(plaintext.size, ciphertextWithTag.size)

        Log.d(TAG, "Nonce: ${nonce.toHexString()}")
        Log.d(TAG, "Tag: ${tag.toHexString()}")

        return EncryptedData(nonce, ciphertext, tag)
    }

    fun decryptAesGcm(encryptedData: EncryptedData, key: ByteArray, aad: ByteArray? = null): ByteArray {
        Log.d(TAG, "Decrypting with AES-128-GCM")
        Log.d(TAG, "Key: ${key.toHexString()}")
        if (aad != null) {
            Log.d(TAG, "AAD: ${aad.toHexString()}")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.nonce)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        if (aad != null) {
            cipher.updateAAD(aad)
        }

        val plaintext = cipher.doFinal(encryptedData.ciphertext + encryptedData.tag)
        Log.d(TAG, "Decrypted size: ${plaintext.size}")
        return plaintext
    }

    fun hmacSha256(key: ByteArray, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload)
    }

    fun deriveHmacSubkey(sessionKey: ByteArray, label: String): ByteArray {
        return hmacSha256(sessionKey, label.toByteArray(Charsets.UTF_8))
    }

    fun generateNonce(length: Int = GCM_IV_LENGTH): ByteArray {
        val nonce = ByteArray(length)
        secureRandom.nextBytes(nonce)
        Log.d(TAG, "Generated nonce: ${nonce.toHexString()}")
        return nonce
    }

    fun ecdsaSign(data: ByteArray): ByteArray {
        Log.d(TAG, "ECDSA signing data: ${data.toHexString()}")
        val javaPrivateKey = java.security.KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(getJavaPrivateKeyEncoded()))
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(javaPrivateKey)
        signer.update(data)
        val signature = signer.sign()
        Log.d(TAG, "ECDSA signature: ${signature.toHexString()}")
        return signature
    }

    private fun getJavaPrivateKeyEncoded(): ByteArray {
        val privateKey = (keyPair?.private as? ECPrivateKeyParameters)?.d
            ?: throw IllegalStateException("Private key not available")

        val ecSpec = java.security.spec.ECParameterSpec(
            java.security.spec.EllipticCurve(
                java.security.spec.ECFieldFp(BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)),
                BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16),
                BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
            ),
            java.security.spec.ECPoint(
                BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
                BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
            ),
            BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16),
            1
        )
        val javaECKey = java.security.spec.ECPrivateKeySpec(privateKey, ecSpec)
        return java.security.KeyFactory.getInstance("EC").generatePrivate(javaECKey).encoded
    }

    data class EncryptedData(
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val tag: ByteArray
    )

    private fun bigIntToBytes(value: BigInteger, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val src = value.toByteArray()
        if (src.size <= length) {
            System.arraycopy(src, 0, bytes, length - src.size, src.size)
        } else {
            System.arraycopy(src, src.size - length, bytes, 0, length)
        }
        return bytes
    }

    private fun stripLeadingZeros(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte()) {
            start++
        }
        return bytes.copyOfRange(start, bytes.size)
    }
}
