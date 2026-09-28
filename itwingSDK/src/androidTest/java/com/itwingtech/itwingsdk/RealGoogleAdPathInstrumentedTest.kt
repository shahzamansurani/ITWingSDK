package com.itwingtech.itwingsdk

import android.view.ViewGroup
import android.widget.FrameLayout
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Network-backed QA: official Google demo IDs tested with a valid custom fallback configured. */
@RunWith(AndroidJUnit4::class)
class RealGoogleAdPathInstrumentedTest {
    @Test
    fun realNativeWinsWhenValidCustomFallbackIsConfigured() {
        assertSdkInitialized()
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            val placement = placement("qa_native_test", "native", NATIVE_TEST_UNIT, withCustomFallback = true)
            val config = ITWingConfig(ads = AdsConfig(globalEnabled = true, placements = listOf(placement)))
            val loader = NativeLoader { config }
            scenario.onActivity { activity ->
                loader.load(
                    activity,
                    activity.adContainer,
                    placement.name,
                    NativeType.SMALL,
                )
            }
            assertLoaded(scenario) { container ->
                container.findDescendant(NativeAdView::class.java) != null
            }
            scenario.onActivity { activity ->
                loader.destroy(activity.adContainer)
                loader.load(activity, activity.adContainer, placement.name, NativeType.LARGE)
            }
            assertLoaded(scenario) { container ->
                container.findDescendant(NativeAdView::class.java) != null
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun realBannerWinsWhenValidCustomFallbackIsConfigured() {
        assertSdkInitialized()
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            val placement = placement("qa_banner_test", "banner", BANNER_TEST_UNIT, withCustomFallback = true)
            val config = ITWingConfig(ads = AdsConfig(globalEnabled = true, placements = listOf(placement)))
            scenario.onActivity { activity ->
                BannerLoader { config }.load(activity, activity.adContainer, placement.name)
            }
            assertLoaded(scenario) { container ->
                container.findDescendant(AdView::class.java) != null
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun terminalNativeAdMobFailureStartsCustomFallback() {
        assertSdkInitialized()
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            val placement = placement("qa_native_forced_failure", "native", INVALID_TEST_UNIT, withCustomFallback = true)
            val config = ITWingConfig(ads = AdsConfig(globalEnabled = true, placements = listOf(placement)))
            val loader = NativeLoader { config }
            scenario.onActivity { activity -> loader.load(activity, activity.adContainer, placement.name, NativeType.SMALL) }
            assertLoaded(scenario) { container ->
                container.findDescendant(NativeAdView::class.java) == null &&
                    (container.findViewById<android.view.View>(R.id.ad_headline) as? android.widget.TextView)
                        ?.text?.toString() == FALLBACK_HEADLINE
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun terminalBannerAdMobFailureStartsCustomFallback() {
        assertSdkInitialized()
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            val placement = placement("qa_banner_forced_failure", "banner", INVALID_TEST_UNIT, withCustomFallback = true)
            val config = ITWingConfig(ads = AdsConfig(globalEnabled = true, placements = listOf(placement)))
            scenario.onActivity { activity -> BannerLoader { config }.load(activity, activity.adContainer, placement.name) }
            assertLoaded(scenario) { container ->
                container.findDescendant(AdView::class.java) == null &&
                    (container.findViewById<android.view.View>(R.id.ad_headline) as? android.widget.TextView)
                        ?.text?.toString() == FALLBACK_HEADLINE
            }
        } finally {
            scenario.close()
        }
    }

    private fun assertSdkInitialized() {
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            val initialized = CountDownLatch(1)
            scenario.onActivity { activity ->
                MobileAds.initialize(
                    activity,
                    InitializationConfig.Builder(TEST_APP_ID).build(),
                ) { initialized.countDown() }
            }
            assertTrue("GMA Next-Gen initialization timed out", initialized.await(30, TimeUnit.SECONDS))
        } finally {
            scenario.close()
        }
    }

    private fun placement(name: String, format: String, unitId: String, withCustomFallback: Boolean = false) = AdPlacementConfig(
        name = name,
        format = format,
        enabled = true,
        testMode = true,
        customAd = if (withCustomFallback) CustomAdConfig(
            id = "must-not-preempt-admob",
            format = format,
            headline = FALLBACK_HEADLINE,
            body = "Local text creative configured as a real fallback.",
            cta = "Open",
            html = "<html><body>valid configured fallback creative</body></html>",
        ) else null,
        units = listOf(AdUnitConfig(network = "admob", adUnitId = unitId, waterfallOrder = 1)),
    )

    private fun assertLoaded(scenario: ActivityScenario<AdColorTestActivity>, loaded: (ViewGroup) -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var succeeded = false
        while (!succeeded && System.nanoTime() < deadline) {
            scenario.onActivity { activity -> succeeded = loaded(activity.adContainer) }
            if (!succeeded) Thread.sleep(250)
        }
        assertTrue("No real Google test-ad view was attached within 60 seconds", succeeded)
    }

    private fun <T : android.view.View> ViewGroup.findDescendant(viewClass: Class<T>): T? {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (viewClass.isInstance(child)) return viewClass.cast(child)
            if (child is ViewGroup) child.findDescendant(viewClass)?.let { return it }
        }
        return null
    }

    private companion object {
        const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val NATIVE_TEST_UNIT = "ca-app-pub-3940256099942544/2247696110"
        const val BANNER_TEST_UNIT = "ca-app-pub-3940256099942544/9214589741"
        const val INVALID_TEST_UNIT = "not-a-valid-admob-unit"
        const val FALLBACK_HEADLINE = "Configured fallback creative"
    }
}
