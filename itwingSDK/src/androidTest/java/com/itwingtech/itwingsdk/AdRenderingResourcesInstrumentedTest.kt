package com.itwingtech.itwingsdk

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.card.MaterialCardView
import com.itwingtech.itwingsdk.ads.AdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdRenderingResourcesInstrumentedTest {
    @Test
    fun nativeRealAndCustomTemplatesInflateWithRequiredAdViews() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val inflater = LayoutInflater.from(context)
            val requiredViewIds = listOf(
                R.id.ad_headline,
                R.id.ad_body,
                R.id.ad_app_icon,
                R.id.ad_media,
                R.id.ad_call_to_action,
            )

            listOf(
                R.layout.native_admob_small,
                R.layout.native_admob_large,
                R.layout.custom_native_small,
                R.layout.custom_native_large,
                R.layout.custom_banner,
            ).forEach { layoutId ->
                val root = inflater.inflate(layoutId, FrameLayout(context), false)
                requiredViewIds.forEach { viewId ->
                    assertNotNull("Layout $layoutId is missing required ad view $viewId", root.findViewById(viewId))
                }
            }
        }
    }

    @Test
    fun restoredTemplatesKeepInnerHierarchyInsideNeutralMaterialCards() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val inflater = LayoutInflater.from(context)
            listOf(
                R.layout.native_admob_small to R.layout.custom_native_small,
                R.layout.native_admob_large to R.layout.custom_native_large,
            ).forEach { (realLayout, customLayout) ->
                val parent = FrameLayout(context)
                val real = inflater.inflate(realLayout, parent, false)
                val custom = inflater.inflate(customLayout, parent, false)
                assertTrue("real template root is a MaterialCardView", real is MaterialCardView)
                assertTrue("custom template root is a MaterialCardView", custom is MaterialCardView)
                assertTrue("Google NativeAdView remains intact", real.findViewById<com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView>(R.id.ad_native_view) != null)
                (real as MaterialCardView).let {
                    assertEquals(0f, it.radius)
                    assertEquals(0f, it.cardElevation)
                    assertEquals(0, it.strokeWidth)
                }
                listOf(R.id.ad_app_icon, R.id.ad_media, R.id.ad_call_to_action).forEach { viewId ->
                    val realView = real.findViewById<android.view.View>(viewId)
                    val customView = custom.findViewById<android.view.View>(viewId)
                    assertNotNull("real layout view $viewId", realView)
                    assertNotNull("custom layout view $viewId", customView)
                    assertEquals("view $viewId width", realView.layoutParams.width, customView.layoutParams.width)
                    assertEquals("view $viewId height", realView.layoutParams.height, customView.layoutParams.height)
                }
            }
            listOf(R.layout.small_shimmer, R.layout.large_shimmer, R.layout.banner_shimmer).forEach { layoutId ->
                assertTrue("shimmer $layoutId uses the designed ShimmerFrameLayout", inflater.inflate(layoutId, FrameLayout(context), false) is ShimmerFrameLayout)
            }
            assertTrue("custom banner uses a MaterialCardView root", inflater.inflate(R.layout.custom_banner, FrameLayout(context), false) is MaterialCardView)
        }
    }

    @Test
    fun configuredShimmerCanBeStyledAndStartedOnDevice() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val shimmer = ShimmerFrameLayout(context)

            AdTheme.styleShimmer(shimmer)
            shimmer.startShimmer()
            assertTrue(shimmer.isShimmerStarted)
            shimmer.stopShimmer()
        }
    }
}
