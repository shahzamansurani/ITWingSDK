package com.itwingtech.itwingsdk.core

interface SDKInitListener {
    fun onReady() {}
    fun onError(error: String) {}
    fun onConfigLoaded(config: ITWingConfig) {}
    fun onAdsReady() {}
    fun onNotificationsReady() {}
    fun onBillingReady() {}
    fun onAnalyticsReady() {}
    fun onOfflineMode(reason: String) {}
    fun onRetry(reason: String) {}
}
