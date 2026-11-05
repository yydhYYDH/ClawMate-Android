package com.clawmate.platform.codex

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hardware-backed [CodexSecretStorage].
 *
 * Backed by an AES-256-GCM [MasterKey] in the Android Keystore. The preferences file is
 * application-private and the host must keep `allowBackup="false"` so a bridge token is
 * never restored onto another device.
 *
 * There is no plaintext fallback: if the keystore cannot be created (locked or the key was
 * invalidated), [open] fails and the caller surfaces the error.
 */
class EncryptedCodexSecretStorage private constructor(
    private val preferences: SharedPreferences,
) : CodexSecretStorage {

    override fun readAll(): Map<String, String> =
        preferences.all.entries.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()

    override fun write(id: String, value: String) {
        if (!preferences.edit().putString(id, value).commit()) {
            throw IllegalStateException("无法保存 Codex 连接信息，请解锁设备后重试")
        }
    }

    override fun remove(id: String) {
        if (!preferences.edit().remove(id).commit()) {
            throw IllegalStateException("无法删除 Codex 连接信息，请解锁设备后重试")
        }
    }

    companion object {
        private const val FILE_NAME = "clawmate_codex_connections"

        /**
         * Opens the encrypted store.
         *
         * @throws IllegalStateException when the keystore key cannot be created or the file
         *   cannot be decrypted; the message says to unlock the device and is safe to show.
         */
        fun open(context: Context): EncryptedCodexSecretStorage {
            val masterKey = try {
                MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            } catch (e: Exception) {
                throw IllegalStateException("无法读取安全连接信息，请解锁设备后重试", e)
            }
            val preferences = try {
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                throw IllegalStateException("无法读取安全连接信息，请解锁设备后重试", e)
            }
            return EncryptedCodexSecretStorage(preferences)
        }
    }
}
