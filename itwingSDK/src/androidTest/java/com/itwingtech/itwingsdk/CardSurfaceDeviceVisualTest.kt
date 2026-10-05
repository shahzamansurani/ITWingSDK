package com.itwingtech.itwingsdk

import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.itwingtech.itwingsdk.ads.BannerLoader
import com.itwingtech.itwingsdk.ads.NativeLoader
import com.itwingtech.itwingsdk.ads.NativeType
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdUnitConfig
import com.itwingtech.itwingsdk.core.AdsConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One-off V2061 visual capture; screenshots are written under the target app's external files. */
@RunWith(AndroidJUnit4::class)
class CardSurfaceDeviceVisualTest {
    @Test
    fun captureRealAndCustomNativeAndBannerWithCardOffAndOn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val initialized = CountDownLatch(1)
        val initScenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            initScenario.onActivity { activity ->
                MobileAds.initialize(activity, InitializationConfig.Builder(TEST_APP_ID).build()) { initialized.countDown() }
            }
            assertTrue("Google demo SDK initialization", initialized.await(30, TimeUnit.SECONDS))
        } finally {
            initScenario.close()
        }

        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/card-surface-v149").close()
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            for (enabled in listOf(false, true)) {
                for (format in listOf("native_small", "native_large", "banner")) {
                    val placement = placement(format, enabled)
                    val config = ITWingConfig(ads = AdsConfig(globalEnabled = true, placements = listOf(placement)))
                    val nativeLoader = NativeLoader { config }
                    val bannerLoader = BannerLoader { config }
                    scenario.onActivity { activity ->
                        activity.adContainer.removeAllViews()
                        if (format == "banner") {
                            bannerLoader.load(activity, activity.adContainer, placement.name)
                        } else {
                            nativeLoader.load(activity, activity.adContainer, placement.name,
                                if (format == "native_small") NativeType.SMALL else NativeType.LARGE)
                        }
                    }
                    assertEventually(scenario) { container ->
                        if (format == "banner") container.contains(AdView::class.java)
                        else container.contains(NativeAdView::class.java)
                    }
                    capture(instrumentation, "${format}_real_${if (enabled) "on" else "off"}.png")
                }

                for (format in listOf("native_small", "native_large", "banner")) {
                    val placement = placement("custom_$format", enabled)
                    val config = ITWingConfig(ads = AdsConfig(globalEnabled = true, placements = listOf(placement)))
                    val nativeLoader = NativeLoader { config }
                    val bannerLoader = BannerLoader { config }
                    val ad = CustomAdConfig(
                        id = "visual-$format", name = "Visual QA creative", format = if (format == "banner") "banner" else "native",
                        headline = "Material card visual QA", body = "Original SDK ad content inside the configured host surface.",
                        cta = "Learn more",
                        mediaUrl = "android.resource://com.itwingtech.itwingsdk.example/${R.drawable.itwing_flow_splash_logo}",
                    )
                    scenario.onActivity { activity ->
                        activity.adContainer.removeAllViews()
                        if (format == "banner") {
                            val render = BannerLoader::class.java.getDeclaredMethod(
                                "renderCustomBanner", android.app.Activity::class.java, ViewGroup::class.java,
                                CustomAdConfig::class.java, AdPlacementConfig::class.java, android.view.View::class.java,
                            ).apply { isAccessible = true }
                            render.invoke(bannerLoader, activity, activity.adContainer, ad, placement, null)
                        } else {
                            val render = NativeLoader::class.java.getDeclaredMethod(
                                "renderCustomNative", android.app.Activity::class.java, ViewGroup::class.java,
                                CustomAdConfig::class.java, AdPlacementConfig::class.java, NativeType::class.java,
                            ).apply { isAccessible = true }
                            render.invoke(nativeLoader, activity, activity.adContainer, ad, placement,
                                if (format == "native_small") NativeType.SMALL else NativeType.LARGE)
                        }
                    }
                    assertEventually(scenario) { it.childCount > 0 }
                    capture(instrumentation, "${format}_custom_${if (enabled) "on" else "off"}.png")
                }
            }
        } finally {
            scenario.close()
        }
    }

    private fun placement(name: String, enabled: Boolean): AdPlacementConfig = AdPlacementConfig(
        name = name,
        format = if (name.contains("banner")) "banner" else "native",
        enabled = true,
        testMode = true,
        units = listOf(AdUnitConfig(network = "admob", adUnitId = if (name.contains("banner")) BANNER_UNIT else NATIVE_UNIT, waterfallOrder = 1)),
        metadata = mapOf(
            "native_card_enabled" to enabled,
            "banner_card_enabled" to enabled,
            "native_card_background_color" to "#FFFDFEFF",
            "banner_card_background_color" to "#FFFDFEFF",
            "native_card_corner_radius" to "12dp",
            "banner_card_corner_radius" to "12dp",
            "native_card_elevation" to "6dp",
            "banner_card_elevation" to "6dp",
        ),
    )

    private fun assertEventually(scenario: ActivityScenario<AdColorTestActivity>, predicate: (ViewGroup) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = predicate(it.adContainer) }
            if (!ready) Thread.sleep(250)
        }
        assertTrue("visual QA ad did not become ready", ready)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(700)
    }

    private fun capture(instrumentation: android.app.Instrumentation, filename: String) {
        instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Download/card-surface-v149/$filename"
        ).close()
        Thread.sleep(500)
    }

    private fun ViewGroup.contains(type: Class<out android.view.View>): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (type.isInstance(child)) return true
            if (child is ViewGroup && child.contains(type)) return true
        }
        return false
    }

    private companion object {
        const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"
        const val BANNER_UNIT = "ca-app-pub-3940256099942544/9214589741"
    }
}
