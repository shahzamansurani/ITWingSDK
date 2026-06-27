package com.itwingtech.itwingsdk.core

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.itwingtech.itwingsdk.data.EncryptedConfigStore

internal object FirebaseRuntimeManager {
    @Volatile
    private var config: FirebaseConfig = FirebaseConfig()

    fun configure(context: Context, firebaseConfig: FirebaseConfig) {
        config = firebaseConfig
        if (!firebaseConfig.enabled) {
            return
        }

        ensureFirebaseApp(context.applicationContext, firebaseConfig)
    }

    fun configureFromCache(context: Context) {
        val cached = runCatching { EncryptedConfigStore(context.applicationContext).load() }.getOrNull()
        cached?.firebase?.let { configure(context.applicationContext, it) }
    }

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
            }
            .build()

        return runCatching { FirebaseApp.initializeApp(context, options) }.getOrNull()
    }
}
