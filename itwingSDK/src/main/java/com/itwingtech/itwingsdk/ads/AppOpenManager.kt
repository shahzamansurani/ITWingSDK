package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.utils.safeCallback
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class AppOpenManager(
    private val configProvider: () -> ITWingConfig,
    private val frequency: FrequencyController
) {
    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val loading =
        AtomicBoolean(false)

    private val automaticStarted =
        AtomicBoolean(false)

    private var loadedPlacement: String? =
        null

    private var appOpenAd: AppOpenAd? =
        null

    private var foregroundActivityRef: WeakReference<Activity>? =
        null

    private val customRenderer =
        CustomFullscreenAdRenderer()

    /*
    |--------------------------------------------------------------------------
    | Start Automatic App Open
    |--------------------------------------------------------------------------
    */

    fun startAutomatic(
        activity: Activity
    ) {
        runOnMain {

            if (!isActivityUsable(activity)) {
                return@runOnMain
            }

            foregroundActivityRef =
                WeakReference(activity)

            runCatching {

                if (
                    !automaticStarted.compareAndSet(
                        false,
                        true
                    )
                ) {

                    preloadAll(activity)
                    return@runCatching
                }

                ProcessLifecycleOwner
                    .get()
                    .lifecycle
                    .addObserver(
                        object : DefaultLifecycleObserver {

                            override fun onStart(
                                owner: LifecycleOwner
                            ) {
                                val currentActivity =
                                    foregroundActivityRef?.get()
                                        ?: return

                                if (!isActivityUsable(currentActivity)) {
                                    return
                                }

                                val placement =
                                    automaticPlacementName()
                                        ?: return

                                show(
                                    currentActivity,
                                    placement
                                )
                            }
                        }
                    )

                preloadAll(activity)
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Preload All
    |--------------------------------------------------------------------------
    */

    fun preloadAll(
        activity: Activity
    ) {
        runOnMain {

            if (!isActivityUsable(activity)) {
                return@runOnMain
            }

            val placement =
                automaticPlacement()
                    ?: return@runOnMain

            preload(
                activity,
                placement.name
            )
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Preload
    |--------------------------------------------------------------------------
    */

    fun preload(
        activity: Activity,
        placementName: String
    ) {
        runOnMain {

            if (!isActivityUsable(activity)) {
                return@runOnMain
            }

            val config =
                safeConfig()
                    ?: return@runOnMain

            if (
                !config.ads.globalEnabled ||
                loading.get() ||
                appOpenAd != null
            ) {
                return@runOnMain
            }

            val placement =
                config.ads.placements.firstOrNull {
                    it.name == placementName &&
                            it.enabled &&
                            it.format == "app_open"
                } ?: return@runOnMain

            if (
                customRenderer.canRender(
                    placement
                )
            ) {

                customRenderer.preload(
                    activity,
                    placement
                )

                loadedPlacement =
                    placementName

                return@runOnMain
            }

            val unit =
                placement.units.firstOrNull {
                    it.network == "admob"
                } ?: return@runOnMain

            loading.set(true)

            runCatching {

                AppOpenAd.load(
                    AdRequest.Builder(
                        unit.adUnitId
                    ).build(),
                    object : AdLoadCallback<AppOpenAd> {

                        override fun onAdLoaded(
                            ad: AppOpenAd
                        ) {
                            runOnMain {

                                loading.set(false)

                                appOpenAd =
                                    ad

                                loadedPlacement =
                                    placementName
                            }
                        }

                        override fun onAdFailedToLoad(
                            adError: LoadAdError
                        ) {
                            loading.set(false)
                        }
                    }
                )

            }.onFailure {

                loading.set(false)
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Show
    |--------------------------------------------------------------------------
    */

    fun show(
        activity: Activity,
        placementName: String = loadedPlacement ?: "app_open",
        onComplete: () -> Unit = {}
    ) {
        runOnMain {

            foregroundActivityRef =
                WeakReference(activity)

            if (!isActivityUsable(activity)) {
                safeCallback(onComplete)
                return@runOnMain
            }

            val config =
                safeConfig()

            if (config == null) {
                safeCallback(onComplete)
                return@runOnMain
            }

            val placement =
                config.ads.placements.firstOrNull {
                    config.ads.globalEnabled &&
                            it.name == placementName &&
                            it.enabled &&
                            it.format == "app_open"
                }

            if (
                placement == null ||
                !frequency.canShow(placement)
            ) {

                safeCallback(onComplete)
                return@runOnMain
            }

            if (
                customRenderer.canRender(
                    placement
                )
            ) {

                frequency.markShown(
                    placement
                )

                customRenderer.show(
                    activity,
                    placement,
                    onComplete = {

                        preload(
                            activity,
                            placementName
                        )

                        safeCallback(
                            onComplete
                        )
                    }
                )

                return@runOnMain
            }

            val ad =
                appOpenAd

            if (ad == null) {

                preload(
                    activity,
                    placementName
                )

                safeCallback(
                    onComplete
                )

                return@runOnMain
            }

            appOpenAd =
                null

            loadedPlacement =
                null

            ad.adEventCallback =
                object : AppOpenAdEventCallback {

                    override fun onAdShowedFullScreenContent() {
                        runOnMain {

                            runCatching {

                                frequency.markShown(
                                    placement
                                )
                            }
                        }
                    }

                    override fun onAdDismissedFullScreenContent() {
                        runOnMain {

                            preload(
                                activity,
                                placementName
                            )

                            safeCallback(
                                onComplete
                            )
                        }
                    }

                    override fun onAdFailedToShowFullScreenContent(
                        fullScreenContentError: FullScreenContentError
                    ) {
                        runOnMain {

                            preload(
                                activity,
                                placementName
                            )

                            safeCallback(
                                onComplete
                            )
                        }
                    }
                }

            if (!isActivityUsable(activity)) {

                safeCallback(
                    onComplete
                )

                return@runOnMain
            }

            runCatching {

                ad.show(
                    activity
                )

            }.onFailure {

                preload(
                    activity,
                    placementName
                )

                safeCallback(
                    onComplete
                )
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Clear
    |--------------------------------------------------------------------------
    */

    fun clear() {
        runOnMain {

            appOpenAd =
                null

            loadedPlacement =
                null

            loading.set(false)
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Automatic Placement
    |--------------------------------------------------------------------------
    */

    private fun automaticPlacementName(): String? {
        return automaticPlacement()
            ?.name
    }

    private fun automaticPlacement() =
        safeConfig()
            ?.ads
            ?.placements
            ?.firstOrNull {
                safeConfig()?.ads?.globalEnabled == true &&
                        it.enabled &&
                        it.format == "app_open" &&
                        it.metadata["splash"].isDisabledByDefault() &&
                        it.metadata["usage"] != "splash" &&
                        it.metadata["show_automatically"].isEnabledByDefault()
            }

    /*
    |--------------------------------------------------------------------------
    | Helpers
    |--------------------------------------------------------------------------
    */

    private fun safeConfig(): ITWingConfig? {
        return runCatching {
            configProvider()
        }.getOrNull()
    }

    private fun runOnMain(
        block: () -> Unit
    ) {
        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {

            block()

        } else {

            mainHandler.post {

                block()
            }
        }
    }

    private fun isActivityUsable(
        activity: Activity
    ): Boolean {
        return !activity.isFinishing &&
                !activity.isDestroyed
    }

    private fun Any?.isEnabledByDefault(): Boolean {
        return when (this) {

            null ->
                true

            is Boolean ->
                this

            is String ->
                !equals(
                    "false",
                    ignoreCase = true
                ) && this != "0"

            is Number ->
                toInt() != 0

            else ->
                true
        }
    }

    private fun Any?.isDisabledByDefault(): Boolean {
        return when (this) {

            null ->
                true

            is Boolean ->
                !this

            is String ->
                equals(
                    "false",
                    ignoreCase = true
                ) || this == "0"

            is Number ->
                toInt() == 0

            else ->
                true
        }
    }
}

//package com.itwingtech.itwingsdk.ads
//
//import android.app.Activity
//import androidx.lifecycle.DefaultLifecycleObserver
//import androidx.lifecycle.LifecycleOwner
//import androidx.lifecycle.ProcessLifecycleOwner
//import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
//import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
//import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
//import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
//import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
//import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
//import com.itwingtech.itwingsdk.core.ITWingConfig
//import com.itwingtech.itwingsdk.utils.runOnMain
//import com.itwingtech.itwingsdk.utils.safeCallback
//import java.util.concurrent.atomic.AtomicBoolean
//
//class AppOpenManager(
//    private val configProvider: () -> ITWingConfig,
//    private val frequency: FrequencyController
//) {
//    private val loading = AtomicBoolean(false)
//    private val automaticStarted = AtomicBoolean(false)
//    private var loadedPlacement: String? = null
//    private var appOpenAd: AppOpenAd? = null
//    private var foregroundActivity: Activity? = null
//    private val customRenderer = CustomFullscreenAdRenderer()
//
//    fun startAutomatic(activity: Activity) {
//        foregroundActivity = activity
//        safeCallback {
//            if (!automaticStarted.compareAndSet(false, true)) {
//                preloadAll(activity)
//                return@safeCallback
//            }
//
//            ProcessLifecycleOwner.get().lifecycle
//                .addObserver(
//                    object : DefaultLifecycleObserver {
//                        override fun onStart(owner: LifecycleOwner) {
//                            val currentActivity = foregroundActivity ?: return
//                            val placement = automaticPlacementName() ?: return
//                            show(currentActivity, placement)
//                        }
//                    },
//                )
//
//            preloadAll(activity)
//        }
//    }
//
//    fun preloadAll(activity: Activity) {
//        val placement = automaticPlacement() ?: return
//        preload(activity, placement.name)
//    }
//
//
//    fun preload(activity: Activity, placementName: String) {
//        val config = configProvider()
//        if (!config.ads.globalEnabled || loading.get() || appOpenAd != null) {
//            return
//        }
//
//        val placement = config.ads.placements.firstOrNull {
//            it.name == placementName &&
//                    it.enabled &&
//                    it.format == "app_open"
//
//        } ?: return
//
//        if (customRenderer.canRender(placement)) {
//            customRenderer.preload(activity, placement)
//            loadedPlacement = placementName
//            return
//        }
//
//        val unit = placement.units.firstOrNull {
//            it.network == "admob"
//        } ?: return
//        loading.set(true)
//        AppOpenAd.load(
//            AdRequest.Builder(unit.adUnitId).build(),
//            object : AdLoadCallback<AppOpenAd> {
//                override fun onAdLoaded(ad: AppOpenAd) {
//                    loading.set(false)
//                    appOpenAd = ad
//                    loadedPlacement = placementName
//                }
//
//                override fun onAdFailedToLoad(
//                    adError: LoadAdError,
//                ) {
//
//                    loading.set(false)
//                }
//            },
//        )
//    }
//
//    fun show(
//        activity: Activity,
//        placementName: String = loadedPlacement ?: "app_open",
//        onComplete: () -> Unit = {},
//    ) {
//        foregroundActivity = activity
//        val config = configProvider()
//        val placement =
//            config.ads.placements.firstOrNull {
//                config.ads.globalEnabled &&
//                        it.name == placementName &&
//                        it.enabled &&
//                        it.format == "app_open"
//            }
//
//        if (placement == null || !frequency.canShow(placement)) {
//            safeCallback(onComplete)
//            return
//        }
//
//        if (customRenderer.canRender(placement)) {
//            frequency.markShown(placement)
//            customRenderer.show(activity, placement, onComplete = {
//                preload(activity, placementName)
//                safeCallback(onComplete)
//            })
//            return
//        }
//
//        val ad = appOpenAd
//        if (ad == null) {
//            preload(activity, placementName)
//            safeCallback(onComplete)
//            return
//        }
//
//        appOpenAd = null
//        loadedPlacement = null
//        ad.adEventCallback =
//            object : AppOpenAdEventCallback {
//                override fun onAdShowedFullScreenContent() {
//                    frequency.markShown(
//                        placement,
//                    )
//                }
//
//                override fun onAdDismissedFullScreenContent() {
//                    preload(activity, placementName)
//                    safeCallback(onComplete)
//                }
//
//                override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
//                    preload(activity, placementName)
//                    safeCallback(onComplete)
//                }
//            }
//
//        runOnMain {
//            ad.show(activity)
//        }
//    }
//
//    fun clear() {
//        appOpenAd = null
//        loadedPlacement = null
//        loading.set(false)
//    }
//
//    private fun automaticPlacementName(): String? {
//        return automaticPlacement()?.name
//    }
//
//    private fun automaticPlacement() =
//        configProvider().ads.placements.firstOrNull {
//            configProvider().ads.globalEnabled &&
//                    it.enabled &&
//                    it.format == "app_open" &&
//                    it.metadata["splash"].isDisabledByDefault() &&
//                    it.metadata["usage"] != "splash" &&
//                    it.metadata["show_automatically"].isEnabledByDefault()
//        }
//
//    private fun Any?.isEnabledByDefault(): Boolean {
//        return when (this) {
//            null -> true
//            is Boolean -> this
//            is String -> !equals("false", ignoreCase = true) && this != "0"
//            is Number -> toInt() != 0
//            else -> true
//        }
//    }
//
//    private fun Any?.isDisabledByDefault(): Boolean {
//        return when (this) {
//            null -> true
//            is Boolean -> !this
//            is String -> equals("false", ignoreCase = true) || this == "0"
//            is Number -> toInt() == 0
//            else -> true
//        }
//    }
//}
