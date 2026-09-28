package com.itwingtech.itwingsdk

import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdUnitConfig
import com.itwingtech.itwingsdk.core.AdsConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reproduces the inline-banner hide/restore path used around ITWingActionDialog. */
@RunWith(AndroidJUnit4::class)
class BannerDialogRestoreInstrumentedTest {
    @Test
    fun realBannerIsReusedAcrossFiftyActionDialogHideRestoreCycles() {
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        val configField = ITWingSDK::class.java.getDeclaredField("config").apply { isAccessible = true }
        val previousConfig = configField.get(ITWingSDK)
        var bannerHost: FrameLayout? = null
        try {
            val placement = AdPlacementConfig(
                name = "qa_banner_dialog_restore",
                format = "banner",
                enabled = true,
                testMode = true,
                units = listOf(AdUnitConfig(
                    network = "admob",
                    adUnitId = "ca-app-pub-3940256099942544/9214589741",
                    waterfallOrder = 1,
                )),
            )
            configField.set(
                ITWingSDK,
                ITWingConfig(
                    app = mapOf("host_dialog" to mapOf("enabled" to true, "review_enabled" to false)),
                    ads = AdsConfig(globalEnabled = true, placements = listOf(placement)),
                ),
            )
            val initialized = CountDownLatch(1)
            scenario.onActivity { activity ->
                MobileAds.initialize(
                    activity,
                    InitializationConfig.Builder("ca-app-pub-3940256099942544~3347511713").build(),
                ) { initialized.countDown() }
            }
            assertTrue("GMA test SDK initializes", initialized.await(30, TimeUnit.SECONDS))

            scenario.onActivity { activity ->
                val wrappedHost = FrameLayout(ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar))
                activity.adContainer.addView(wrappedHost, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                ITWingSDK.ads.loadBanner(activity, wrappedHost, placement.name)
            }
            scenario.onActivity { activity -> bannerHost = activity.adContainer.getChildAt(0) as FrameLayout }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var loadedBanner: AdView? = null
            while (loadedBanner == null && System.nanoTime() < deadline) {
                scenario.onActivity { loadedBanner = bannerHost?.findDescendant(AdView::class.java) }
                if (loadedBanner == null) Thread.sleep(200)
            }
            assertNotNull("official Google banner test ad attaches", loadedBanner)

            scenario.onActivity { activity ->
                repeat(50) { cycle ->
                    val dialog = ITWingSDK.createActionDialog(activity).setReviewEnabled(false)
                    dialog.show(nativePlacement = "qa_missing_native_placement", nativeType = "small")
                    val host = requireNotNull(bannerHost)
                    assertEquals("banner hidden in dialog cycle $cycle", View.GONE, host.visibility)
                    dialog.dismiss()
                    assertEquals("banner restored in dialog cycle $cycle", View.VISIBLE, host.visibility)
                    assertSame("same banner view reused in cycle $cycle", loadedBanner, host.findDescendant(AdView::class.java))
                    assertEquals("one banner view in cycle $cycle", 1, host.countDescendants(AdView::class.java))
                    if (cycle == 24) {
                        activity.adContainer.removeView(host)
                        activity.adContainer.addView(host)
                    }
                }
            }
        } finally {
            scenario.onActivity { activity -> bannerHost?.let(ITWingSDK.ads::destroyBanner) }
            configField.set(ITWingSDK, previousConfig)
            scenario.close()
        }
    }

    private fun <T : View> ViewGroup.findDescendant(type: Class<T>): T? {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (type.isInstance(child)) return type.cast(child)
            if (child is ViewGroup) child.findDescendant(type)?.let { return it }
        }
        return null
    }

    private fun <T : View> ViewGroup.countDescendants(type: Class<T>): Int {
        var count = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (type.isInstance(child)) count++
            if (child is ViewGroup) count += child.countDescendants(type)
        }
        return count
    }
}
