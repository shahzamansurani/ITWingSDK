package com.itwingtech.itwingsdk

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itwingtech.itwingsdk.core.ApiKeyConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Models an eager DI provider -> Retrofit base URL -> ViewModel startup call chain. */
@RunWith(AndroidJUnit4::class)
class ApiBaseUrlStartupInstrumentedTest {
    @Test
    fun apiUrlProviderIsSafeBeforeAndAfterConfigAndAcrossRepeatedActivityStartup() {
        val configField = ITWingSDK::class.java.getDeclaredField("config").apply { isAccessible = true }
        val previousConfig = configField.get(ITWingSDK)
        val pool = Executors.newSingleThreadExecutor()
        try {
            configField.set(ITWingSDK, ITWingConfig())
            assertEquals("", ITWingSDK.getApiBaseUrl("rates"))
            ActivityScenario.launch(AdColorTestActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val model = StartupViewModel(ApiModule.provideRetrofitBaseUrl())
                    assertEquals("https://fallback.example/", model.api.baseUrl)
                }
            }
            val backgroundValue = pool.submit<String> {
                ApiModule.provideRetrofitBaseUrl().baseUrl
            }.get(5, TimeUnit.SECONDS)
            assertEquals("https://fallback.example/", backgroundValue)

            configField.set(
                ITWingSDK,
                ITWingConfig(apiKeys = mapOf("rates" to ApiKeyConfig(name = "rates", baseUrl = "https://api.example.test/v1"))),
            )
            repeat(10) { recreation ->
                ActivityScenario.launch(AdColorTestActivity::class.java).use { scenario ->
                    scenario.onActivity {
                        val model = StartupViewModel(ApiModule.provideRetrofitBaseUrl())
                        assertEquals("activity recreation $recreation", "https://api.example.test/v1/", model.api.baseUrl)
                        model.api.baseUrl.toHttpUrl()
                    }
                }
            }
            repeat(20) { launch ->
                val cleanLaunch = ActivityScenario.launch(AdColorTestActivity::class.java)
                try {
                    cleanLaunch.onActivity {
                        val model = StartupViewModel(ApiModule.provideRetrofitBaseUrl())
                        assertEquals("startup launch $launch", "https://api.example.test/v1/", model.api.baseUrl)
                    }
                } finally {
                    cleanLaunch.close()
                }
            }
        } finally {
            pool.shutdownNow()
            configField.set(ITWingSDK, previousConfig)
        }
    }

    /** Minimal constructor-injected provider mirroring the host's eager Retrofit DI provider. */
    private object ApiModule {
        fun provideRetrofitBaseUrl() = RetrofitBaseUrl(
            ITWingSDK.getApiBaseUrl("rates", "https://fallback.example/"),
        )
    }

    private data class RetrofitBaseUrl(val baseUrl: String)
    private class StartupViewModel(val api: RetrofitBaseUrl)
}
