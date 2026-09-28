package com.itwingtech.itwingsdk

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import androidx.cardview.widget.CardView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.shimmer.ShimmerFrameLayout
import com.itwingtech.itwingsdk.ads.AdTheme
import com.itwingtech.itwingsdk.ads.BannerLoader
import com.itwingtech.itwingsdk.ads.NativeLoader
import com.itwingtech.itwingsdk.ads.NativeType
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdsConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdColorRenderingInstrumentedTest {
    @Test
    fun cardSurfaceUsesAdminStyleAndCanBeExplicitlyFlattened() {
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val card = CardView(activity)
                val colors = mapOf(
                    "native_card_enabled" to "true",
                    "native_card_background_color" to "#FFFAFAFA",
                    "native_card_corner_radius" to "large",
                    "native_card_elevation" to "medium",
                    "native_card_border_color" to "#FF123456",
                    "native_card_border_width" to "thin",
                    "native_card_padding" to "compact",
                )
                val config = ITWingConfig(app = mapOf("colors" to colors), ads = AdsConfig(globalEnabled = true))
                val configField = ITWingSDK::class.java.getDeclaredField("config").apply { isAccessible = true }
                val previousConfig = configField.get(ITWingSDK)
                try {
                    configField.set(ITWingSDK, config)
                    AdTheme.applyCard(card, "native")
                    val density = activity.resources.displayMetrics.density
                    assertEquals(16f * density, card.radius)
                    assertEquals(6f * density, card.cardElevation)
                    assertNotNull(card.foreground)
                    assertEquals((8f * density).toInt(), card.contentPaddingLeft)

                    AdTheme.applyCard(card, "native", mapOf(
                        "native_card_elevation" to "none",
                        "native_card_corner_radius" to "none",
                        "native_card_border_width" to "none",
                        "native_card_padding" to "none",
                    ))
                    assertEquals(0f, card.cardElevation)
                    assertEquals(0f, card.radius)
                    assertEquals(null, card.foreground)
                    assertEquals(0, card.getContentPaddingLeft())
                } finally {
                    configField.set(ITWingSDK, previousConfig)
                }
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun nativeAndBannerDefaultsMatchApprovedCardSurfaceWithoutAdminOverrides() {
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val configField = ITWingSDK::class.java.getDeclaredField("config").apply { isAccessible = true }
                val previousConfig = configField.get(ITWingSDK)
                try {
                    configField.set(ITWingSDK, ITWingConfig(ads = AdsConfig(globalEnabled = true)))
                    val nativeStyle = AdTheme.cardStyle("native")
                    val bannerStyle = AdTheme.cardStyle("banner")
                    assertEquals(Color.TRANSPARENT, nativeStyle.backgroundColor)
                    assertEquals(0f, nativeStyle.cornerRadiusDp)
                    assertEquals(5f, nativeStyle.elevationDp)
                    assertEquals(0f, nativeStyle.borderWidthDp)
                    assertEquals(true, nativeStyle.explicitlyConfigured)
                    assertEquals(true, nativeStyle.enabled)
                    assertEquals(Color.TRANSPARENT, bannerStyle.backgroundColor)
                    assertEquals(0f, bannerStyle.cornerRadiusDp)
                    assertEquals(5f, bannerStyle.elevationDp)
                    assertEquals(0f, bannerStyle.borderWidthDp)
                    assertEquals(true, bannerStyle.explicitlyConfigured)
                    assertEquals(true, bannerStyle.enabled)
                    val enabledStyle = AdTheme.cardStyle("native", mapOf("native_card_enabled" to true))
                    assertEquals(true, enabledStyle.enabled)
                    assertEquals(0f, enabledStyle.cornerRadiusDp)
                    assertEquals(5f, enabledStyle.elevationDp)
                    assertEquals(false, enabledStyle.backgroundConfigured)
                    val themedCard = android.view.LayoutInflater.from(activity)
                        .inflate(R.layout.native_admob_small, activity.adContainer, false) as com.google.android.material.card.MaterialCardView
                    AdTheme.applyCard(themedCard, "native", mapOf("native_card_enabled" to true))
                    assertTrue("enabled card gets a visible theme surface", themedCard.cardBackgroundColor.defaultColor != Color.TRANSPARENT)
                    assertEquals(0f, themedCard.radius)
                    assertEquals(5f * activity.resources.displayMetrics.density, themedCard.cardElevation)
                    val enabledBannerStyle = AdTheme.cardStyle("banner", mapOf("banner_card_enabled" to true))
                    assertEquals(4f, enabledBannerStyle.innerPaddingDp)
                    val disabledStyle = AdTheme.cardStyle("native", mapOf(
                        "native_card_enabled" to false,
                        "native_card_corner_radius" to "large",
                        "native_card_elevation" to "strong",
                    ))
                    assertEquals(true, disabledStyle.enabled)
                    assertEquals(16f, disabledStyle.cornerRadiusDp)
                    assertEquals(10f, disabledStyle.elevationDp)

                    val host = FrameLayout(activity)
                    val originalContent = View(activity)
                    AdTheme.attachCardSurface(host, originalContent, "native")
                    val defaultCard = host.getChildAt(0) as CardView
                    assertEquals("the original content remains unchanged inside the card", originalContent, defaultCard.getChildAt(0))
                    assertEquals(5f * activity.resources.displayMetrics.density, defaultCard.cardElevation)

                    val configuredCard = CardView(activity)
                    AdTheme.applyCard(configuredCard, "native", mapOf(
                        "native_card_enabled" to "true",
                        "native_card_background_color" to "transparent",
                        "native_card_corner_radius" to "none",
                        "native_card_elevation" to "none",
                        "native_card_border_color" to "transparent",
                        "native_card_border_width" to "none",
                    ))
                    assertEquals(Color.TRANSPARENT, configuredCard.cardBackgroundColor.defaultColor)
                    assertEquals(0f, configuredCard.radius)
                    assertEquals(0f, configuredCard.cardElevation)
                    assertEquals(null, configuredCard.foreground)
                } finally {
                    configField.set(ITWingSDK, previousConfig)
                }
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun sharedRendererInstallsARealMaterialCardForGenericAndSdkHosts() {
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val colors = mapOf(
                    "native_card_enabled" to "true",
                    "banner_card_enabled" to "true",
                    "native_card_background_color" to "#FFFAFAFA",
                    "native_card_corner_radius" to "medium",
                    "native_card_elevation" to "strong",
                    "banner_card_elevation" to "strong",
                )
                val config = ITWingConfig(app = mapOf("colors" to colors), ads = AdsConfig(globalEnabled = true))
                val configField = ITWingSDK::class.java.getDeclaredField("config").apply { isAccessible = true }
                val previousConfig = configField.get(ITWingSDK)
                try {
                    configField.set(ITWingSDK, config)
                    val genericHost = FrameLayout(activity)
                    val nativeContent = TextView(activity).apply { text = "Native creative" }
                    AdTheme.attachCardSurface(genericHost, nativeContent, "native")
                    val surface = genericHost.getChildAt(0) as CardView
                    assertEquals(10f * activity.resources.displayMetrics.density, surface.cardElevation)
                    assertEquals(12f * activity.resources.displayMetrics.density, surface.radius)
                    assertEquals(nativeContent, surface.getChildAt(0))
                    assertEquals(false, surface.isClickable)

                    val sdkHost = CardView(activity)
                    val bannerContent = FrameLayout(activity)
                    AdTheme.attachCardSurface(sdkHost, bannerContent, "banner", clipContent = false)
                    assertEquals(10f * activity.resources.displayMetrics.density, sdkHost.cardElevation)
                    assertEquals(bannerContent, sdkHost.getChildAt(0))
                    assertEquals(false, sdkHost.clipToOutline)
                } finally {
                    configField.set(ITWingSDK, previousConfig)
                }
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun shimmerViewReceivesAdminConfiguredBaseAndHighlightColors() {
        val colors = mapOf(
            "media_shimmer_base_color" to "#102030",
            "media_shimmer_highlight_color" to "#405060",
        )
        val config = ITWingConfig(app = mapOf("colors" to colors), ads = AdsConfig(globalEnabled = true))
        val configField = ITWingSDK::class.java.getDeclaredField("config").apply { isAccessible = true }
        val previousConfig = configField.get(ITWingSDK)
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)

        try {
            configField.set(ITWingSDK, config)
            scenario.onActivity { activity ->
                val shimmerView = ShimmerFrameLayout(activity)
                AdTheme.styleShimmer(shimmerView)

                val drawableField = ShimmerFrameLayout::class.java.getDeclaredField("mShimmerDrawable")
                    .apply { isAccessible = true }
                val drawable = drawableField.get(shimmerView)
                val shimmerField = drawable.javaClass.getDeclaredField("mShimmer")
                    .apply { isAccessible = true }
                val shimmer = shimmerField.get(drawable)
                val baseColor = shimmer.javaClass.getDeclaredField("baseColor")
                    .apply { isAccessible = true }
                    .getInt(shimmer)
                val highlightColor = shimmer.javaClass.getDeclaredField("highlightColor")
                    .apply { isAccessible = true }
                    .getInt(shimmer)

                // ColorHighlightBuilder intentionally preserves the library's base alpha
                // and replaces only its RGB channels for this shimmer implementation.
                assertEquals(
                    Color.parseColor(colors.getValue("media_shimmer_base_color")) and 0x00FFFFFF,
                    baseColor and 0x00FFFFFF,
                )
                assertEquals(Color.parseColor(colors.getValue("media_shimmer_highlight_color")), highlightColor)
            }
        } finally {
            configField.set(ITWingSDK, previousConfig)
            scenario.close()
        }
    }

    @Test
    fun appColorsAreAppliedByRealNativePlacementStylerForSmallAndLarge() {
        val colors = mapOf(
            "native_text_color" to "#FF112233",
            "native_secondary_text_color" to "#FF223344",
            "native_meta_text_color" to "#FF334455",
            "native_background_color" to "#FF445566",
            "native_stroke_color" to "#FF556677",
            "native_ad_label_background_color" to "#FF667788",
            "native_ad_label_text_color" to "#FF778899",
            "native_cta_color" to "#FF8899AA",
            "native_cta_text_color" to "#FF99AABB",
        )
        val config = ITWingConfig(app = mapOf("colors" to colors), ads = AdsConfig(globalEnabled = true))
        val configField = ITWingSDK::class.java.getDeclaredField("config").apply { isAccessible = true }
        val previousConfig = configField.get(ITWingSDK)
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)

        try {
            configField.set(ITWingSDK, config)
            scenario.onActivity { activity ->
                val styler = NativeLoader { config }
                val style = NativeLoader::class.java.getDeclaredMethod(
                    "applyNativePlacementStyle",
                    android.view.View::class.java,
                    Map::class.java,
                ).apply { isAccessible = true }
                val metadata = mapOf("native_transparent_background" to false)

                listOf(R.layout.native_admob_small, R.layout.native_admob_large).forEach { layoutId ->
                    val root = android.view.LayoutInflater.from(activity)
                        .inflate(layoutId, activity.adContainer, false)
                    val nativeAdView = root.findViewById<View>(R.id.ad_native_view)
                    style.invoke(styler, nativeAdView, metadata)

                    assertEquals("legacy configured native background for $layoutId", Color.parseColor(colors.getValue("native_background_color")), (nativeAdView.background as ColorDrawable).color)
                    assertEquals(
                        "real native stroke token for $layoutId",
                        Color.parseColor(colors.getValue("native_stroke_color")),
                        AdTheme.nativeStroke(),
                    )
                    assertEquals(
                        "real native headline for $layoutId",
                        Color.parseColor(colors.getValue("native_text_color")),
                        root.findViewById<TextView>(R.id.ad_headline).currentTextColor,
                    )
                    assertEquals(
                        "real native body for $layoutId",
                        Color.parseColor(colors.getValue("native_secondary_text_color")),
                        root.findViewById<TextView>(R.id.ad_body).currentTextColor,
                    )
                    assertEquals(
                        "real native meta for $layoutId",
                        Color.parseColor(colors.getValue("native_meta_text_color")),
                        root.findViewById<TextView>(R.id.ad_advertiser).currentTextColor,
                    )
                    val host = FrameLayout(activity)
                    AdTheme.attachCardSurface(host, root, "native", metadata, legacyStroke = AdTheme.nativeStroke())
                    assertEquals(
                        "legacy native color remains on the original layout for $layoutId",
                        Color.parseColor(colors.getValue("native_background_color")),
                        (nativeAdView.background as ColorDrawable).color,
                    )
                }
            }
        } finally {
            configField.set(ITWingSDK, previousConfig)
            scenario.close()
        }
    }

    @Test
    fun appColorsAreAppliedByRealSmallAndLargeCustomNativeRenderers() {
        val colors = mapOf(
            "native_text_color" to "#FF112233",
            "native_secondary_text_color" to "#FF223344",
            "native_meta_text_color" to "#FF334455",
            "native_background_color" to "#FF445566",
            "native_stroke_color" to "#FF556677",
            "native_ad_label_text_color" to "#FF667788",
            "native_ad_label_background_color" to "#FF778899",
            "native_cta_color" to "#FF8899AA",
            "native_cta_text_color" to "#FF99AABB",
        )
        val config = ITWingConfig(
            app = mapOf("colors" to colors),
            ads = AdsConfig(globalEnabled = true),
        )
        val placement = AdPlacementConfig(
            name = "instrumented_color_test",
            format = "native",
            enabled = true,
            testMode = true,
            metadata = mapOf("native_transparent_background" to false),
        )
        val ad = CustomAdConfig(
            id = "local-test-ad",
            name = "Local test ad",
            format = "native",
            headline = "Color headline",
            body = "Color body",
            cta = "Continue",
            mediaUrl = null,
        )

        val configField = ITWingSDK::class.java.getDeclaredField("config").apply { isAccessible = true }
        val previousConfig = configField.get(ITWingSDK)
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            configField.set(ITWingSDK, config)
            scenario.onActivity { activity ->
                val loader = NativeLoader { config }
                val render = NativeLoader::class.java.getDeclaredMethod(
                    "renderCustomNative",
                    Activity::class.java,
                    ViewGroup::class.java,
                    CustomAdConfig::class.java,
                    AdPlacementConfig::class.java,
                    NativeType::class.java,
                ).apply { isAccessible = true }

                listOf(NativeType.SMALL, NativeType.LARGE).forEach { type ->
                    render.invoke(loader, activity, activity.adContainer, ad, placement, type)
                    val surface = activity.adContainer.getChildAt(0) as CardView
                    val root = surface.getChildAt(0)
                    assertNotNull("$type template should render", root)
                    assertEquals(
                        "$type app-config background",
                        Color.parseColor(colors.getValue("native_background_color")),
                        surface.cardBackgroundColor.defaultColor,
                    )
                    assertEquals(
                        "$type headline color",
                        Color.parseColor(colors.getValue("native_text_color")),
                        root.findViewById<TextView>(R.id.ad_headline).currentTextColor,
                    )
                    assertEquals(
                        "$type body color",
                        Color.parseColor(colors.getValue("native_secondary_text_color")),
                        root.findViewById<TextView>(R.id.ad_body).currentTextColor,
                    )
                    assertEquals(
                        "$type advertiser/meta color",
                        Color.parseColor(colors.getValue("native_meta_text_color")),
                        root.findViewById<TextView>(R.id.ad_advertiser).currentTextColor,
                    )
                    val cta = root.findViewById<TextView>(R.id.ad_call_to_action)
                    assertEquals(
                        "$type CTA text color",
                        Color.parseColor(colors.getValue("native_cta_text_color")),
                        cta.currentTextColor,
                    )
                    assertEquals(
                        "$type CTA background",
                        Color.parseColor(colors.getValue("native_cta_color")),
                        (cta.background as GradientDrawable).color?.defaultColor,
                    )
                    val label = root.findViewById<TextView>(R.id.ad_ic)
                    assertEquals(
                        "$type label text color",
                        Color.parseColor(colors.getValue("native_ad_label_text_color")),
                        label.currentTextColor,
                    )
                    assertEquals(
                        "$type label background",
                        Color.parseColor(colors.getValue("native_ad_label_background_color")),
                        (label.background as GradientDrawable).color?.defaultColor,
                    )
                    assertEquals(
                        "$type configured card stroke resolves from app config",
                        Color.parseColor(colors.getValue("native_stroke_color")),
                        AdTheme.nativeStroke(),
                    )
                }
            }
        } finally {
            configField.set(ITWingSDK, previousConfig)
            scenario.close()
        }
    }

    @Test
    fun appColorsAreAppliedByCustomBannerRenderer() {
        val colors = mapOf(
            "banner_text_color" to "#FF112233",
            "secondary_text_color" to "#FF223344",
            "banner_background_color" to "#FF334455",
            "banner_stroke_color" to "#FF445566",
            "banner_cta_color" to "#FF556677",
            "banner_cta_text_color" to "#FF667788",
            "native_ad_label_background_color" to "#FF778899",
            "ad_label_text_color" to "#FF8899AA",
        )
        val config = ITWingConfig(
            app = mapOf("colors" to colors),
            ads = AdsConfig(globalEnabled = true),
        )
        val placement = AdPlacementConfig(
            name = "instrumented_banner_color_test",
            format = "banner",
            enabled = true,
            testMode = true,
            metadata = mapOf("banner_transparent_background" to false),
        )
        val ad = CustomAdConfig(
            id = "local-test-banner",
            name = "Local banner",
            format = "banner",
            headline = "Banner headline",
            body = "Banner body",
            cta = "Continue",
            mediaUrl = null,
        )

        val configField = ITWingSDK::class.java.getDeclaredField("config").apply { isAccessible = true }
        val previousConfig = configField.get(ITWingSDK)
        val scenario = ActivityScenario.launch(AdColorTestActivity::class.java)
        try {
            configField.set(ITWingSDK, config)
            scenario.onActivity { activity ->
                val loader = BannerLoader { config }
                val render = BannerLoader::class.java.getDeclaredMethod(
                    "renderCustomBanner",
                    Activity::class.java,
                    ViewGroup::class.java,
                    CustomAdConfig::class.java,
                    AdPlacementConfig::class.java,
                    android.view.View::class.java,
                ).apply { isAccessible = true }

                render.invoke(loader, activity, activity.adContainer, ad, placement, null)
                val card = activity.adContainer.getChildAt(0) as CardView
                val root = card.getChildAt(0)
                assertNotNull("banner template should render", root)
                assertEquals(
                    "banner background",
                    Color.parseColor(colors.getValue("banner_background_color")),
                    card.cardBackgroundColor.defaultColor,
                )
                assertEquals(
                    "banner stroke key resolves from app config",
                    Color.parseColor(colors.getValue("banner_stroke_color")),
                    AdTheme.bannerStroke(),
                )
                assertEquals(
                    "banner headline",
                    Color.parseColor(colors.getValue("banner_text_color")),
                    root.findViewById<TextView>(R.id.ad_headline).currentTextColor,
                )
                assertEquals(
                    "banner body",
                    Color.parseColor(colors.getValue("secondary_text_color")),
                    root.findViewById<TextView>(R.id.ad_body).currentTextColor,
                )
                val cta = root.findViewById<TextView>(R.id.ad_call_to_action)
                assertEquals(
                    "banner CTA background",
                    Color.parseColor(colors.getValue("banner_cta_color")),
                    (cta.background as GradientDrawable).color?.defaultColor,
                )
                assertEquals(
                    "banner CTA text",
                    Color.parseColor(colors.getValue("banner_cta_text_color")),
                    cta.currentTextColor,
                )
                val label = root.findViewById<TextView>(R.id.ad_ic)
                assertEquals(
                    "banner label background",
                    Color.parseColor(colors.getValue("native_ad_label_background_color")),
                    (label.background as GradientDrawable).color?.defaultColor,
                )
                assertEquals(
                    "banner label text",
                    Color.parseColor(colors.getValue("ad_label_text_color")),
                    label.currentTextColor,
                )
            }
        } finally {
            configField.set(ITWingSDK, previousConfig)
            scenario.close()
        }
    }
}
