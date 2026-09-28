package com.itwingtech.itwingsdk

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itwingtech.itwingsdk.ads.AdManager
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdManagerInvalidActivityInstrumentedTest {
    @Test
    fun nullActivityDegradesToCallbacksOrNoOpAtPublicEntryPoints() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = AdManager(configProvider = { ITWingConfig() })
            var completed = 0
            var unavailable = 0

            manager.showInterstitial(activity = null, placement = "test") { completed++ }
            manager.showAppOpen(activity = null, placement = "test") { completed++ }
            manager.showRewarded(
                activity = null,
                placement = "test",
                onReward = {},
                onUnavailableOrSkipped = { unavailable++ },
            )
            manager.showRewardedDirect(
                activity = null,
                placement = "test",
                onReward = {},
                onUnavailableOrSkipped = { unavailable++ },
            )
            manager.showRewarded(activity = null, placement = "test", onComplete = { completed++ })
            manager.showRewardedInterstitial(activity = null, placement = "test", onComplete = { completed++ })
            manager.preloadInterstitial(activity = null, placement = "test")
            manager.preloadRewarded(activity = null, placement = "test")
            manager.preloadRewardedInterstitial(activity = null, placement = "test")
            manager.preloadAppOpen(activity = null, placement = "test")
            manager.preloadAll(activity = null)
            manager.startAutomaticAppOpen(activity = null)
            manager.updateForegroundActivity(activity = null)
            ITWingSDK.showInterstitial(activity = null, placement = "test") { completed++ }
            ITWingSDK.showAppOpen(activity = null, placement = "test") { completed++ }

            assertEquals("non-rewarded public show completion remains deterministic", 4, completed)
            assertEquals("rewarded calls report unavailable", 2, unavailable)
        }
    }

    @Test
    fun destroyedActivityDoesNotEscapeThroughInterstitialSdkEntryPoint() {
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        var destroyedActivity: Activity? = null
        scenario.onActivity { activity ->
            destroyedActivity = activity
            activity.finish()
        }
        scenario.close()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var completed = false
            ITWingSDK.showInterstitial(activity = destroyedActivity, placement = "test") {
                completed = true
            }
            assertEquals("destroyed Activity should complete without presenting", true, completed)
        }
    }
}
