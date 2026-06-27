package com.itwingtech.itwingsdk.core

import android.app.Activity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

data class ITWingAppFlowOptions @JvmOverloads constructor(
    val splashLogo: Int = 0,
    val splashBackground: Int = 0,
    val splashStyle: String? = null,
    val splashTitle: String? = null,
    val splashSubtitle: String? = null,
    val splashLottieUrl: String? = null,
    val onboardingImages: List<Int> = emptyList(),
    val onboardingPages: List<ITWingOnboardingPage> = emptyList(),
    val onboardingBannerPlacement: String? = "banner_adaptive",
    val termsBannerPlacement: String? = "banner_adaptive",
    val termsInterstitialPlacement: String? = "interstitial",
    val requireTerms: Boolean = true,
    val showOnboarding: Boolean = true,
)

data class ITWingOnboardingPage @JvmOverloads constructor(
    val title: String,
    val description: String,
    val imageResId: Int = 0,
    val imageUrl: String? = null,
    val nativePlacement: String? = null,
)

internal data class ITWingAppFlowSession(
    val apiKey: String,
    val sdkOptions: ITWingOptions,
    val flowOptions: ITWingAppFlowOptions,
    val mainActivityName: String,
    val listener: SDKInitListener? = null,
)

internal object ITWingAppFlowRegistry {
    private val sessions = ConcurrentHashMap<String, ITWingAppFlowSession>()

    fun put(session: ITWingAppFlowSession): String {
        val id = UUID.randomUUID().toString()
        sessions[id] = session
        return id
    }

    fun get(id: String?): ITWingAppFlowSession? {
        if (id.isNullOrBlank()) return null
        return sessions[id]
    }

    fun remove(id: String?) {
        if (!id.isNullOrBlank()) sessions.remove(id)
    }
}

fun <T : Activity> KClass<T>.asJavaActivity(): Class<T> = java
