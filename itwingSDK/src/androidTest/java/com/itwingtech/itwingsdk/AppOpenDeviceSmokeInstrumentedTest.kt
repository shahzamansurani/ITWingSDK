package com.itwingtech.itwingsdk

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.itwingtech.itwingsdk.ads.AppOpenManager
import com.itwingtech.itwingsdk.ads.FrequencyController
import com.itwingtech.itwingsdk.ads.FullscreenAdState
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdUnitConfig
import com.itwingtech.itwingsdk.core.AdsConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppOpenDeviceSmokeInstrumentedTest {
    @Test
    fun officialGoogleAppOpenTestAdPresentsOnlyForResumedForegroundActivity() {
        val scenario = ActivityScenario.launch(AppOpenQaActivity::class.java)
        try {
            val initialized = CountDownLatch(1)
            scenario.onActivity { activity ->
                MobileAds.initialize(
                    activity,
                    InitializationConfig.Builder(TEST_APP_ID).build(),
                ) { initialized.countDown() }
            }
            assertTrue("GMA test SDK initializes", initialized.await(30, TimeUnit.SECONDS))

            val placement = AdPlacementConfig(
                name = "qa_app_open_foreground",
                format = "app_open",
                enabled = true,
                testMode = true,
                units = listOf(AdUnitConfig(
                    network = "admob",
                    adUnitId = APP_OPEN_TEST_UNIT,
                    waterfallOrder = 1,
                )),
            )
            val config = ITWingConfig(ads = AdsConfig(globalEnabled = true, placements = listOf(placement)))
            val manager = AppOpenManager({ config }, FrequencyController())
            scenario.onActivity { activity ->
                manager.updateForegroundActivity(activity)
                manager.preload(activity, placement.name)
            }
            val loadedField = AppOpenManager::class.java.getDeclaredField("appOpenAd").apply { isAccessible = true }
            val loadDeadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(60)
            while (loadedField.get(manager) == null && SystemClock.elapsedRealtime() < loadDeadline) Thread.sleep(200)
            assertTrue("official Google App Open test ad loads", loadedField.get(manager) != null)

            scenario.onActivity { activity ->
                val session = AppOpenManager::class.java.getDeclaredField("foregroundSessionId").apply { isAccessible = true }.getLong(manager)
                val gate = AppOpenManager::class.java.getDeclaredMethod(
                    "appOpenRejectionReason",
                    android.app.Activity::class.java,
                    java.lang.Long.TYPE,
                    java.lang.Boolean.TYPE,
                ).apply { isAccessible = true }
                val reason = gate.invoke(manager, activity, session, false)
                // Library instrumentation runs under the test package's process lifecycle;
                // it is not a faithful foregrounded host process. Verify the production
                // guard fails closed in that state instead of bypassing it to force a show.
                assertTrue(
                    "App Open must be blocked when process lifecycle is ${androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState}",
                    reason == "SKIPPED_BACKGROUND",
                )
                manager.show(activity, placement.name, {}, waitForLoad = false)
            }
            assertTrue("background policy rejection must not reserve/show fullscreen", !FullscreenAdState.isActive())
        } finally {
            scenario.close()
        }
    }

    private companion object {
        const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val APP_OPEN_TEST_UNIT = "ca-app-pub-3940256099942544/9257395921"
    }
}
