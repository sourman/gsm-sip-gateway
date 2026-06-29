package com.callagent.gateway.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest store for SIP credentials.
 *
 * Backed by [EncryptedSharedPreferences] (AndroidX Security) using a master key
 * held in the Android Keystore. The plaintext "gateway" prefs are migrated once
 * and then cleared.
 *
 * Fail-closed: if the encrypted store cannot be opened (e.g. a corrupted
 * Keystore on a broken device), [open] throws rather than falling back to the
 * plaintext prefs. Callers must treat the absence of an encrypted store as
 * "no credentials" and refuse to register.
 */
object CredentialStore {

    private const val TAG = "CredentialStore"

    /** Legacy plaintext prefs written by the Activity before encryption. */
    private const val LEGACY_PREFS = "gateway"

    /** Encrypted prefs file name. */
    private const val ENCRYPTED_PREFS = "gateway_secure"

    /** Marker key set in the encrypted store once migration has completed. */
    private const val KEY_MIGRATED = "migrated_from_legacy"

    /**
     * Opens the encrypted credential store, performing a one-time migration of
     * any plaintext credentials found in the legacy "gateway" prefs.
     *
     * @return the encrypted [SharedPreferences]; never null.
     * @throws CredentialStoreException if the encrypted store cannot be
     *   initialized. Callers must fail closed — do NOT fall back to plaintext.
     */
    fun open(context: Context): SharedPreferences {
        val encrypted = try {
            create(context)
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted store init failed — refusing to use plaintext", e)
            throw CredentialStoreException("Encrypted credential store unavailable", e)
        }

        migrateFromLegacy(context, encrypted)
        return encrypted
    }

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * One-time, idempotent migration of plaintext credentials.
     *
     * Copies server/port/user/pass/local_server/autoconnect from the legacy
     * "gateway" prefs into the encrypted store (only if the encrypted store
     * does not already carry them), then deletes the plaintext values.
     *
     * Safe to call repeatedly: the [KEY_MIGRATED] marker guards the copy, and
     * deleting already-absent keys is a no-op.
     */
    private fun migrateFromLegacy(context: Context, encrypted: SharedPreferences) {
        if (encrypted.getBoolean(KEY_MIGRATED, false)) {
            return
        }

        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacyKeys = listOf("server", "port", "user", "pass", "local_server", "autoconnect")
        val hasAnyLegacy = legacyKeys.any { legacy.contains(it) }

        val editor = encrypted.edit()
        if (hasAnyLegacy) {
            // Copy each key that the encrypted store does not already hold,
            // so a partially-completed prior migration is not overwritten.
            if (!encrypted.contains("server") && legacy.contains("server"))
                editor.putString("server", legacy.getString("server", null))
            if (!encrypted.contains("port") && legacy.contains("port"))
                editor.putInt("port", legacy.getInt("port", 5060))
            if (!encrypted.contains("user") && legacy.contains("user"))
                editor.putString("user", legacy.getString("user", null))
            if (!encrypted.contains("pass") && legacy.contains("pass"))
                editor.putString("pass", legacy.getString("pass", null))
            if (!encrypted.contains("local_server") && legacy.contains("local_server"))
                editor.putBoolean("local_server", legacy.getBoolean("local_server", false))
            if (!encrypted.contains("autoconnect") && legacy.contains("autoconnect"))
                editor.putBoolean("autoconnect", legacy.getBoolean("autoconnect", true))
            Log.i(TAG, "Migrated ${legacyKeys.count { legacy.contains(it) }} credential key(s) from legacy prefs")
        }

        // Wipe plaintext credentials regardless — once migrated, the legacy
        // prefs must hold no secrets. Non-credential keys (if any) are left.
        val legacyEditor = legacy.edit()
        legacyKeys.forEach { legacyEditor.remove(it) }
        legacyEditor.apply()

        editor.putBoolean(KEY_MIGRATED, true)
        editor.apply()
    }
}

/** Raised when the encrypted credential store cannot be opened. */
class CredentialStoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
