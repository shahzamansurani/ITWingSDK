package com.itwingtech.itwingsdk.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.itwingtech.itwingsdk.core.ITWingConfig
import androidx.core.content.edit
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore



class EncryptedConfigStore(context: Context) {
    private val gson = Gson()
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = createPreferences(appContext)

    fun save(config: ITWingConfig) {
        prefs.edit {
            putInt("config_version", config.configVersion)
                .putInt("ttl_seconds", config.ttlSeconds)
                .putBoolean("global_enabled", config.ads.globalEnabled)
                .putString("config_json", gson.toJson(config))
        }
    }

    fun load(): ITWingConfig? {
        val json = prefs.getString("config_json", null) ?: return null
        return runCatching { gson.fromJson(json, ITWingConfig::class.java) }.getOrNull()
    }

    fun saveEntitlement(active: Boolean, removesAds: Boolean, expiresAtIso: String?) {
        prefs.edit {
            putBoolean("entitlement_active", active)
                .putBoolean("entitlement_removes_ads", removesAds)
                .putString("entitlement_expires_at", expiresAtIso)
                .putLong("entitlement_checked_at", System.currentTimeMillis())
        }
    }

    fun isAdFreeEntitled(): Boolean {
        if (!prefs.getBoolean("entitlement_active", false) || !prefs.getBoolean("entitlement_removes_ads", false)) {
            return false
        }
        val expiresAt = prefs.getString("entitlement_expires_at", null) ?: return true
        return runCatching {
            java.time.Instant.parse(expiresAt).toEpochMilli() > System.currentTimeMillis()
        }.getOrDefault(false)
    }

    fun installId(): String? = prefs.getString("install_id", null)

    fun saveInstallId(value: String) {
        prefs.edit { putString("install_id", value) }
    }

    fun consumeFirstOpen(): Boolean {
        val alreadySent = prefs.getBoolean("first_open_sent", false)
        if (alreadySent) return false
        prefs.edit { putBoolean("first_open_sent", true) }
        return true
    }

    fun appendAnalyticsEvent(event: JSONObject, maxQueueSize: Int = 500) {
        val current = analyticsEvents().toMutableList()
        current.add(event)
        val trimmed = current.takeLast(maxQueueSize)
        prefs.edit { putString("analytics_queue", JSONArray(trimmed).toString()) }
    }

    fun analyticsEvents(limit: Int = 50): List<JSONObject> {
        val raw = prefs.getString("analytics_queue", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .take(limit)
    }

    fun removeAnalyticsEvents(count: Int) {
        val remaining = analyticsEvents(Int.MAX_VALUE).drop(count)
        prefs.edit { putString("analytics_queue", JSONArray(remaining).toString()) }
    }

    private fun createPreferences(context: Context): SharedPreferences {
        return runCatching { encryptedPreferences(context) }
            .recoverCatching { firstFailure ->
                Log.w(TAG, "Encrypted SDK store is unavailable, resetting local SDK cache.", firstFailure)
                resetEncryptedStore(context)
                encryptedPreferences(context)
            }
            .getOrElse { secondFailure ->
                Log.e(TAG, "Encrypted SDK store could not be recreated. Falling back to isolated plain SDK cache.", secondFailure)
                context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            }
    }

    private fun encryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun resetEncryptedStore(context: Context) {
        runCatching { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit() }
        runCatching { deleteSharedPrefsFile(context, PREFS_NAME) }
        runCatching { deleteKeyStoreEntry(MASTER_KEY_ALIAS) }
        runCatching { deleteKeyStoreEntry(LEGACY_MASTER_KEY_ALIAS) }
    }

    private fun deleteSharedPrefsFile(context: Context, name: String) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        File(dir, "$name.xml").delete()
        File(dir, "$name.xml.bak").delete()
    }

    private fun deleteKeyStoreEntry(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private companion object {
        private const val TAG = "ITWingEncryptedStore"
        private const val PREFS_NAME = "itwing_ads_config"
        private const val FALLBACK_PREFS_NAME = "itwing_ads_config_recovered"
        private const val MASTER_KEY_ALIAS = "itwing_ads_master_key"
        private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }
}
