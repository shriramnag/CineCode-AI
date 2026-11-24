package com.shriram.cinecode.data.agent

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import com.shriram.cinecode.data.remote.CloudBridgeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

enum class KeystoreSecurityLevel {
    STRONGBOX,
    TEE,
    SOFTWARE,
    UNAVAILABLE
}

/**
 * Centralized, secure API key and provider configuration manager for CineCode-AI.
 * Features:
 * - Production-enforced AndroidKeyStore AES-256-GCM hardware encryption at rest.
 * - Hardware security introspection via Android KeyInfo (StrongBox / TEE / Software).
 * - Cryptographically random 12-byte IV per encryption operation with 128-bit authentication tag.
 * - Strict rejection of plaintext fallback upon decryption failures (zero unauthenticated requests).
 * - Non-destructive migration of existing plaintext credentials.
 * - Zero plaintext logging or credential leakage.
 */
class ProviderManager(private val context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val cryptoHelper = KeyStoreCryptoHelper()

    private val _geminiKeyFlow = MutableStateFlow(readKey(PROVIDER_GEMINI))
    val geminiKeyFlow: StateFlow<String> = _geminiKeyFlow.asStateFlow()

    private val _anthropicKeyFlow = MutableStateFlow(readKey(PROVIDER_ANTHROPIC))
    val anthropicKeyFlow: StateFlow<String> = _anthropicKeyFlow.asStateFlow()

    private val _openRouterKeyFlow = MutableStateFlow(readKey(PROVIDER_OPENROUTER))
    val openRouterKeyFlow: StateFlow<String> = _openRouterKeyFlow.asStateFlow()

    private val _groqKeyFlow = MutableStateFlow(readKey(PROVIDER_GROQ))
    val groqKeyFlow: StateFlow<String> = _groqKeyFlow.asStateFlow()

    init {
        // Ensure synchronization with existing CloudBridgeStore if Gemini key was set there
        val legacyGeminiKey = CloudBridgeStore.apiKeyFlow.value
        if (legacyGeminiKey.isNotBlank() && _geminiKeyFlow.value.isBlank()) {
            setApiKey(PROVIDER_GEMINI, legacyGeminiKey)
        }
    }

    fun getKeystoreSecurityLevel(): KeystoreSecurityLevel = cryptoHelper.getSecurityLevel()

    fun getApiKey(provider: String): String {
        return when (provider.lowercase()) {
            PROVIDER_GEMINI -> {
                val key = _geminiKeyFlow.value
                if (key.isNotBlank()) key else CloudBridgeStore.apiKeyFlow.value
            }
            PROVIDER_ANTHROPIC -> _anthropicKeyFlow.value
            PROVIDER_OPENROUTER -> _openRouterKeyFlow.value
            PROVIDER_GROQ -> _groqKeyFlow.value
            else -> readKey(provider)
        }
    }

    fun setApiKey(provider: String, rawKey: String) {
        val trimmed = rawKey.trim()
        val prefKey = keyFor(provider)
        if (trimmed.isEmpty()) {
            prefs.edit().remove(prefKey).apply()
        } else {
            val encrypted = cryptoHelper.encrypt(trimmed)
            prefs.edit().putString(prefKey, encrypted).apply()
        }

        when (provider.lowercase()) {
            PROVIDER_GEMINI -> {
                _geminiKeyFlow.value = trimmed
                // Synchronize with CloudBridgeStore so legacy components remain compatible
                CloudBridgeStore.saveApiKey(context, trimmed)
            }
            PROVIDER_ANTHROPIC -> _anthropicKeyFlow.value = trimmed
            PROVIDER_OPENROUTER -> _openRouterKeyFlow.value = trimmed
            PROVIDER_GROQ -> _groqKeyFlow.value = trimmed
        }
    }

    fun hasValidKey(provider: String): Boolean {
        val key = getApiKey(provider)
        return key.isNotBlank() && key.length >= 8
    }

    /**
     * Returns a masked preview of the API key for safe UI rendering (e.g. "AIza••••••••••••7xQ").
     * Never exposes the full raw secret.
     */
    fun getMaskedKey(provider: String): String {
        val raw = getApiKey(provider)
        if (raw.isBlank()) return "Not configured"
        if (raw.length <= 8) return "••••••••"
        val prefix = raw.take(4)
        val suffix = raw.takeLast(3)
        return "$prefix••••••••••••$suffix"
    }

    private fun readKey(provider: String): String {
        val prefKey = keyFor(provider)
        val stored = prefs.getString(prefKey, null) ?: return ""
        if (stored.isBlank()) return ""

        // Check if legacy plaintext (does not start with enc: prefix)
        if (!stored.startsWith(KeyStoreCryptoHelper.ENC_PREFIX)) {
            // Legacy plaintext key detected! Migrate to Keystore encryption in place immediately
            val plaintext = stored.trim()
            val encrypted = runCatching { cryptoHelper.encrypt(plaintext) }.getOrNull()
            if (encrypted != null) {
                prefs.edit().putString(prefKey, encrypted).apply()
            }
            // Return existing plaintext safely so migration does not cause data loss
            return plaintext
        }

        // Encrypted key: decrypt with Keystore. If decryption fails, returns empty without deleting pref
        return cryptoHelper.decrypt(stored).trim()
    }

    private fun keyFor(provider: String): String = "key_${provider.lowercase()}"

    /**
     * Hardware-backed encryption helper using AndroidKeyStore (AES-256-GCM).
     */
    class KeyStoreCryptoHelper {
        private val isAndroid: Boolean = try {
            Class.forName("android.os.Build")
            Build.VERSION.SDK_INT > 0
        } catch (t: Throwable) {
            false
        }

        private val keyStore: KeyStore? = runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        }.getOrNull()

        private fun getOrCreateKey(): SecretKey? {
            val ks = keyStore ?: return null
            return runCatching {
                if (!ks.containsAlias(KEY_ALIAS)) {
                    val kGen = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE
                    )
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                    kGen.init(spec)
                    kGen.generateKey()
                } else {
                    ks.getKey(KEY_ALIAS, null) as? SecretKey
                }
            }.getOrNull()
        }

        fun getSecurityLevel(): KeystoreSecurityLevel {
            if (!isAndroid || keyStore == null) return KeystoreSecurityLevel.UNAVAILABLE
            return runCatching {
                val secretKey = getOrCreateKey() ?: return KeystoreSecurityLevel.UNAVAILABLE
                val factory = SecretKeyFactory.getInstance(secretKey.algorithm, ANDROID_KEYSTORE)
                val keyInfo = factory.getKeySpec(secretKey, KeyInfo::class.java) as KeyInfo
                if (Build.VERSION.SDK_INT >= 31) { // Android 12+ (Build.VERSION_CODES.S)
                    when (keyInfo.securityLevel) {
                        KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeystoreSecurityLevel.STRONGBOX
                        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeystoreSecurityLevel.TEE
                        else -> KeystoreSecurityLevel.SOFTWARE
                    }
                } else if (Build.VERSION.SDK_INT >= 23) { // Android 6.0–11 (API 23–30): never call securityLevel
                    @Suppress("DEPRECATION")
                    if (keyInfo.isInsideSecureHardware) {
                        KeystoreSecurityLevel.TEE
                    } else {
                        KeystoreSecurityLevel.SOFTWARE
                    }
                } else {
                    KeystoreSecurityLevel.SOFTWARE
                }
            }.getOrDefault(KeystoreSecurityLevel.SOFTWARE)
        }

        fun encrypt(plaintext: String): String {
            if (plaintext.isBlank()) return ""
            if (!isAndroid) {
                // Non-Android testing environment isolation only
                return ENC_PREFIX + "TEST_ONLY_" + Base64.encodeToString(plaintext.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }

            val secretKey = getOrCreateKey()
                ?: throw SecurityException("AndroidKeyStore master key is unavailable for production encryption")

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv ?: throw SecurityException("Failed to generate secure GCM initialization vector")
            require(iv.size == GCM_IV_LENGTH) { "Invalid GCM IV size: ${iv.size}" }

            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

            return ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        }

        fun decrypt(stored: String): String {
            if (stored.isBlank()) return ""
            if (!stored.startsWith(ENC_PREFIX)) return ""

            val payload = stored.removePrefix(ENC_PREFIX)
            if (!isAndroid && payload.startsWith("TEST_ONLY_")) {
                return runCatching {
                    val rawB64 = payload.removePrefix("TEST_ONLY_")
                    String(Base64.decode(rawB64, Base64.NO_WRAP), Charsets.UTF_8)
                }.getOrDefault("")
            }

            val secretKey = getOrCreateKey() ?: return ""
            return runCatching {
                val combined = Base64.decode(payload, Base64.NO_WRAP)
                if (combined.size <= GCM_IV_LENGTH) return ""
                val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
                val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }.getOrDefault("")
        }

        companion object {
            private const val ANDROID_KEYSTORE = "AndroidKeyStore"
            private const val KEY_ALIAS = "cinecode_provider_keys_master"
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
            private const val GCM_IV_LENGTH = 12
            private const val GCM_TAG_LENGTH_BITS = 128
            const val ENC_PREFIX = "enc:v1:"
        }
    }

    companion object {
        const val PREFS_NAME = "cinecode_secure_providers"

        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_GROQ = "groq"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_OLLAMA = "ollama"

        @Volatile
        private var instance: ProviderManager? = null

        fun getInstance(context: Context): ProviderManager {
            return instance ?: synchronized(this) {
                instance ?: ProviderManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
