package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts Bridge authentication tokens at rest using AES-256-GCM
 * backed by AndroidKeyStore (with an app-sandboxed 256-bit key fallback when
 * running under local JVM/Robolectric test environments).
 */
class TokenCryptoManager(private val context: Context) {

    private val random = SecureRandom()

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val key = getOrCreateSecretKey()
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (_: Exception) {
            // AndroidKeyStore keys generate their own IV unless overridden
        }
        val effectiveCipher = Cipher.getInstance(TRANSFORMATION)
        val encryptedBytes: ByteArray
        val actualIv: ByteArray
        if (isKeyStoreKey(key)) {
            effectiveCipher.init(Cipher.ENCRYPT_MODE, key)
            encryptedBytes = effectiveCipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            actualIv = effectiveCipher.iv ?: iv
        } else {
            effectiveCipher.init(Cipher.ENCRYPT_MODE, key, spec)
            encryptedBytes = effectiveCipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            actualIv = iv
        }
        val ivB64 = Base64.encodeToString(actualIv, Base64.NO_WRAP)
        val cipherB64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        return "$PREFIX_V1$ivB64:$cipherB64"
    }

    fun decrypt(encodedCipherText: String): String? {
        if (encodedCipherText.isBlank()) return null
        if (!encodedCipherText.startsWith(PREFIX_V1)) return null
        val payload = encodedCipherText.removePrefix(PREFIX_V1)
        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) return null

        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        getAndroidKeyStoreSecretKey()?.let { return it }
        return getOrCreateFallbackKey()
    }

    private fun isKeyStoreKey(key: SecretKey): Boolean {
        return key.javaClass.name.contains("AndroidKeyStore", ignoreCase = true)
    }

    private fun getAndroidKeyStoreSecretKey(): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
            if (existing != null) return@runCatching existing

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            generator.generateKey()
        }.getOrNull()
    }

    private fun getOrCreateFallbackKey(): SecretKey {
        val baseDir = context.noBackupFilesDir ?: context.filesDir
        val keyFile = File(baseDir, FALLBACK_KEY_FILE)
        val keyBytes = if (keyFile.exists()) {
            runCatching {
                Base64.decode(keyFile.readText(Charsets.UTF_8).trim(), Base64.NO_WRAP)
            }.getOrNull()?.takeIf { it.size == 32 }
        } else {
            null
        } ?: ByteArray(32).also { bytes ->
            random.nextBytes(bytes)
            runCatching {
                keyFile.parentFile?.mkdirs()
                keyFile.writeText(Base64.encodeToString(bytes, Base64.NO_WRAP), Charsets.UTF_8)
                keyFile.setReadable(true, true)
                keyFile.setWritable(true, true)
            }
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "muse_bridge_auth_token_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PREFIX_V1 = "v1:"
        private const val FALLBACK_KEY_FILE = "muse_bridge_token_key.bin"

        fun sha256Hex(rawToken: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(rawToken.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun previewToken(rawToken: String): String {
            if (rawToken.length <= 14) return rawToken
            return "${rawToken.take(10)}...${rawToken.takeLast(4)}"
        }
    }
}
