package org.fossify.messages.gateway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The operator's configuration, plus the catch-up cursors. The token lives in [TokenVault], not here. */
class GatewaySettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(ENABLED, false)
        set(value) = prefs.edit { putBoolean(ENABLED, value) }

    var baseUrl: String
        get() = prefs.getString(BASE_URL, "").orEmpty()
        set(value) = prefs.edit { putString(BASE_URL, value) }

    /** The phone's own numbers as the operator typed them, one per line. */
    var ownNumbers: List<String>
        get() = prefs.getString(OWN_NUMBERS, "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = prefs.edit { putString(OWN_NUMBERS, value.joinToString("\n")) }

    var subscriptionId: Int
        get() = prefs.getInt(SUBSCRIPTION_ID, NO_SUBSCRIPTION)
        set(value) = prefs.edit { putInt(SUBSCRIPTION_ID, value) }

    /** Messages dated before this are never reported, so turning the gateway on doesn't upload old history. */
    var enabledAt: Long
        get() = prefs.getLong(ENABLED_AT, Long.MAX_VALUE)
        set(value) = prefs.edit { putLong(ENABLED_AT, value) }

    /** Highest uploaded SMS and MMS provider ids. The catch-up scanner reads past them. */
    var smsCursor: Long
        get() = prefs.getLong(SMS_CURSOR, 0)
        set(value) = prefs.edit { putLong(SMS_CURSOR, value) }

    var mmsCursor: Long
        get() = prefs.getLong(MMS_CURSOR, 0)
        set(value) = prefs.edit { putLong(MMS_CURSOR, value) }

    var lastThreadsSentAt: Long
        get() = prefs.getLong(LAST_THREADS_AT, 0)
        set(value) = prefs.edit { putLong(LAST_THREADS_AT, value) }

    /** Group threads already in the last thread list; a new one triggers a fresh list. */
    var knownGroupThreads: Set<String>
        get() = prefs.getStringSet(KNOWN_GROUP_THREADS, emptySet()).orEmpty()
        set(value) = prefs.edit { putStringSet(KNOWN_GROUP_THREADS, value) }

    @Synchronized
    fun advanceCursors(sms: Long?, mms: Long?) {
        if (sms != null && sms > smsCursor) smsCursor = sms
        if (mms != null && mms > mmsCursor) mmsCursor = mms
    }

    companion object {
        const val NO_SUBSCRIPTION = -1
        private const val PREFS = "gateway_settings"
        private const val ENABLED = "enabled"
        private const val BASE_URL = "base_url"
        private const val OWN_NUMBERS = "own_numbers"
        private const val SUBSCRIPTION_ID = "subscription_id"
        private const val ENABLED_AT = "enabled_at"
        private const val SMS_CURSOR = "sms_cursor"
        private const val MMS_CURSOR = "mms_cursor"
        private const val LAST_THREADS_AT = "last_threads_at"
        private const val KNOWN_GROUP_THREADS = "known_group_threads"

        /** Cheap check for the receivers. */
        fun isEnabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    }
}

/**
 * Keeps the device token encrypted with an AES key held in the Android Keystore. The token is never logged and
 * never shown in full; [hint] gives the operator enough to recognise it.
 */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cached: String? = null

    @Synchronized
    fun get(): String? {
        cached?.let { return it }
        val stored = prefs.getString(TOKEN, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8).also { cached = it }
        } catch (_: GeneralSecurityException) {
            // The key is gone (e.g. restored from a backup onto another device): the operator must enter it again.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Synchronized
    fun set(token: String?) {
        cached = null
        if (token.isNullOrBlank()) {
            prefs.edit { remove(TOKEN) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        prefs.edit { putString(TOKEN, Base64.encodeToString(blob, Base64.NO_WRAP)) }
        cached = token.trim()
    }

    fun hint(): String? = get()?.let { token ->
        val prefix = token.substringBefore('_', "").takeIf { it.isNotEmpty() }?.let { "${it}_" }.orEmpty()
        "$prefix…${token.takeLast(HINT_CHARS)}"
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return try {
            generator.init(spec)
            generator.generateKey()
        } catch (e: ProviderException) {
            throw GeneralSecurityException("keystore unavailable", e)
        }
    }

    companion object {
        private const val PREFS = "gateway_secret"
        private const val TOKEN = "token"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dude_gateway_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val HINT_CHARS = 4
    }
}
