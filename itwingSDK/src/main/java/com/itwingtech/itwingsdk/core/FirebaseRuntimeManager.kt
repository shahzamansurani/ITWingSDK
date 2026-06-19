package com.itwingtech.itwingsdk.core

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.itwingtech.itwingsdk.data.EncryptedConfigStore

internal object FirebaseRuntimeManager {
    @Volatile
    private var config: FirebaseConfig = FirebaseConfig()
    @Volatile
    private var analyticsInstance: FirebaseAnalytics? = null
    @Volatile
    private var crashlyticsInstance: Any? = null
    @Volatile
    private var authInstance: FirebaseAuth? = null

    fun configure(context: Context, firebaseConfig: FirebaseConfig) {
        config = firebaseConfig
        if (!firebaseConfig.enabled) {
            analyticsInstance = null
            crashlyticsInstance = null
            authInstance = null
            return
        }

        val app = ensureFirebaseApp(context.applicationContext, firebaseConfig) ?: return

        analyticsInstance = if (firebaseConfig.analyticsEnabled || firebaseConfig.roiCampaignsEnabled) {
            runCatching { FirebaseAnalytics.getInstance(context.applicationContext) }.getOrNull()
        } else {
            null
        }

        crashlyticsInstance = if (firebaseConfig.crashlyticsEnabled) {
            optionalCrashlyticsInstance()
        } else {
            null
        }

        authInstance = if (firebaseConfig.authEnabled) {
            runCatching { FirebaseAuth.getInstance(app) }.getOrNull()
        } else {
            null
        }
    }

    fun configureFromCache(context: Context) {
        if (analyticsInstance != null || crashlyticsInstance != null || authInstance != null) return
        val cached = runCatching { EncryptedConfigStore(context.applicationContext).load() }.getOrNull()
        cached?.firebase?.let { configure(context.applicationContext, it) }
    }

    fun logEvent(name: String, properties: Map<String, Any?> = emptyMap()) {
        val active = config
        if (!active.enabled || (!active.analyticsEnabled && !active.roiCampaignsEnabled)) return
        val analytics = analyticsInstance ?: return
        runCatching {
            val bundle = Bundle()
            properties.forEach { (key, value) ->
                val safeKey = key.take(40)
                when (value) {
                    null -> Unit
                    is Boolean -> bundle.putString(safeKey, value.toString())
                    is Int -> bundle.putInt(safeKey, value)
                    is Long -> bundle.putLong(safeKey, value)
                    is Float -> bundle.putFloat(safeKey, value)
                    is Double -> bundle.putDouble(safeKey, value)
                    is Number -> bundle.putDouble(safeKey, value.toDouble())
                    else -> bundle.putString(safeKey, value.toString().take(100))
                }
            }
            analytics.logEvent(name.take(40), bundle)
        }
    }

    fun recordNonFatal(throwable: Throwable, properties: Map<String, Any?> = emptyMap()) {
        if (!config.enabled || !config.crashlyticsEnabled) return
        val crashlytics = crashlyticsInstance ?: return
        runCatching {
            properties.forEach { (key, value) ->
                crashlytics.javaClass
                    .getMethod("setCustomKey", String::class.java, String::class.java)
                    .invoke(crashlytics, key.take(40), value?.toString()?.take(100) ?: "")
            }
            crashlytics.javaClass
                .getMethod("recordException", Throwable::class.java)
                .invoke(crashlytics, throwable)
        }
    }

    fun auth(): FirebaseAuth? = authInstance

    private fun ensureFirebaseApp(context: Context, firebaseConfig: FirebaseConfig): FirebaseApp? {
        runCatching { FirebaseApp.getInstance() }.getOrNull()?.let { return it }

        val apiKey = firebaseConfig.apiKey?.takeIf { it.isNotBlank() } ?: return null
        val appId = firebaseConfig.googleAppId?.takeIf { it.isNotBlank() } ?: return null
        val projectId = firebaseConfig.projectId?.takeIf { it.isNotBlank() } ?: return null

        val options = FirebaseOptions.Builder()
            .setApiKey(apiKey)
            .setApplicationId(appId)
            .setProjectId(projectId)
            .apply {
                firebaseConfig.gcmSenderId?.takeIf { it.isNotBlank() }?.let { setGcmSenderId(it) }
                firebaseConfig.storageBucket?.takeIf { it.isNotBlank() }?.let { setStorageBucket(it) }
            }
            .build()

        return runCatching { FirebaseApp.initializeApp(context, options) }.getOrNull()
    }

    private fun optionalCrashlyticsInstance(): Any? {
        return runCatching {
            val crashlyticsClass = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val crashlytics = crashlyticsClass.getMethod("getInstance").invoke(null)
            runCatching {
                crashlyticsClass
                    .getMethod("setCrashlyticsCollectionEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(crashlytics, true)
            }
            crashlytics
        }.getOrNull()
    }
}
