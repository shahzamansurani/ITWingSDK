package com.itwingtech.itwingsdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdThemeTest {
    @Test
    fun appOpenResumeRequiresARealBackgroundSessionAndMinimumDuration() {
        assertEquals(false, AppOpenPresentationPolicy.shouldAutomaticallyPresentResume(false, 60_000))
        assertEquals(false, AppOpenPresentationPolicy.shouldAutomaticallyPresentResume(true, 1_999))
        assertEquals(true, AppOpenPresentationPolicy.shouldAutomaticallyPresentResume(true, 2_000))
    }

    @Test
    fun appOpenIsSuppressedUntilACompletedPreviousAppSession() {
        assertEquals(false, AppOpenPresentationPolicy.mayPresentFirstEverLaunch(false))
        assertEquals(true, AppOpenPresentationPolicy.mayPresentFirstEverLaunch(true))
        assertEquals(
            "SKIPPED_FIRST_EVER_LAUNCH",
            AppOpenPresentationPolicy.rejectionReason(
                processForeground = true,
                activityResumedAndUsable = true,
                foregroundSessionMatches = true,
                fullscreenConflict = false,
                firstEverLaunch = true,
            ),
        )
    }

    @Test
    fun parsesSupportedRgbAndArgbFormats() {
        assertEquals(0xFFAABBCC.toInt(), AdTheme.parseHex("#abc"))
        assertEquals(0x88AABBCC.toInt(), AdTheme.parseHex("#8AbC"))
        assertEquals(0xFF112233.toInt(), AdTheme.parseHex("112233"))
        assertEquals(0x80112233.toInt(), AdTheme.parseHex("#80112233"))
    }

    @Test
    fun rejectsMalformedValuesWithoutThrowing() {
        listOf(null, "", "   ", "red", "#12", "#GGGGGG", "#123456789").forEach { value ->
            assertNull("Expected invalid color $value to be rejected", AdTheme.parseHex(value))
        }
    }

    @Test
    fun invalidColorUsesProvidedFallback() {
        assertEquals(0xFF336699.toInt(), AdTheme.safeColor("not-a-color", 0xFF336699.toInt()))
    }

    @Test
    fun componentMetadataWinsOverAppColors() {
        val appColors = mapOf("native_cta_color" to "#010203", "primary" to "#040506")
        val resolved = AdTheme.color(
            fallback = 0,
            appKeys = listOf("native_cta_color", "primary"),
            metadata = mapOf("native_cta_color" to "#A1B2C3"),
            metadataKeys = listOf("native_cta_color"),
            appColorProvider = appColors::get,
        )

        assertEquals(0xFFA1B2C3.toInt(), resolved)
    }

    @Test
    fun malformedSpecificColorFallsThroughToNextConfiguredAlias() {
        val appColors = mapOf("native_cta_color" to "#oops", "primary" to "#123456")
        val resolved = AdTheme.color(
            fallback = 0,
            appKeys = listOf("native_cta_color", "primary"),
            metadata = mapOf("native_cta_color" to 42),
            metadataKeys = listOf("native_cta_color"),
            appColorProvider = appColors::get,
        )

        assertEquals(0xFF123456.toInt(), resolved)
    }

    @Test
    fun nativeCtaUsesAccentAfterDedicatedAndPrimaryAliases() {
        val appColors = mapOf("native_cta_color" to " ", "primary" to "invalid", "primary_color" to "", "accent" to "#765432")

        assertEquals(
            0xFF765432.toInt(),
            AdTheme.nativeCtaColor(appColorProvider = appColors::get),
        )
    }

    @Test
    fun bannerSecondaryTextUsesGenericAdminAlias() {
        val appColors = mapOf("secondary_text_color" to "#345678")

        assertEquals(
            0xFF345678.toInt(),
            AdTheme.bannerSecondary(fallback = 0, appColorProvider = appColors::get),
        )
    }

    @Test
    fun everyBackendDefinedAdColorRoleResolvesFromAppColors() {
        val appColors = mapOf(
            "native_text_color" to "#010101",
            "native_secondary_text_color" to "#020202",
            "native_meta_text_color" to "#030303",
            "native_background_color" to "#040404",
            "native_stroke_color" to "#050505",
            "native_ad_label_background_color" to "#060606",
            "native_ad_label_text_color" to "#070707",
            "native_cta_color" to "#080808",
            "native_cta_text_color" to "#090909",
            "banner_text_color" to "#0A0A0A",
            "banner_background_color" to "#0B0B0B",
            "banner_stroke_color" to "#0C0C0C",
            "banner_cta_color" to "#0D0D0D",
            "banner_cta_text_color" to "#0E0E0E",
            "cta_text_color" to "#0F0F0F",
            "ad_label_text_color" to "#101010",
        )
        val color: (String) -> String? = appColors::get

        assertEquals(0xFF010101.toInt(), AdTheme.nativeText(fallback = 0, appColorProvider = color))
        assertEquals(0xFF020202.toInt(), AdTheme.nativeBody(fallback = 0, appColorProvider = color))
        assertEquals(0xFF030303.toInt(), AdTheme.nativeMeta(fallback = 0, appColorProvider = color))
        assertEquals(0xFF040404.toInt(), AdTheme.nativeBackground(appColorProvider = color))
        assertEquals(0xFF050505.toInt(), AdTheme.nativeStroke(appColorProvider = color))
        assertEquals(0xFF060606.toInt(), AdTheme.nativeLabelColor(appColorProvider = color))
        assertEquals(0xFF070707.toInt(), AdTheme.nativeLabelTextColor(appColorProvider = color))
        assertEquals(0xFF080808.toInt(), AdTheme.nativeCtaColor(appColorProvider = color))
        assertEquals(0xFF090909.toInt(), AdTheme.nativeCtaTextColor(appColorProvider = color))
        assertEquals(0xFF0A0A0A.toInt(), AdTheme.bannerText(fallback = 0, appColorProvider = color))
        assertEquals(0xFF0B0B0B.toInt(), AdTheme.bannerBackground(appColorProvider = color))
        assertEquals(0xFF0C0C0C.toInt(), AdTheme.bannerStroke(appColorProvider = color))
        assertEquals(0xFF0D0D0D.toInt(), AdTheme.bannerCtaColor(appColorProvider = color))
        assertEquals(0xFF0E0E0E.toInt(), AdTheme.bannerCtaText(appColorProvider = color))
        assertEquals(0xFF070707.toInt(), AdTheme.bannerLabelTextColor(appColorProvider = color))
        assertEquals(0xFF101010.toInt(), AdTheme.bannerLabelTextColor { appColors["ad_label_text_color"] })
    }

    @Test
    fun genericApplicationPaletteControlsAllAdSurfacesAndText() {
        val colors = mapOf(
            "background" to "#F1F2F3",
            "text" to "#111213",
            "secondary" to "#454647",
        )
        val provider: (String) -> String? = colors::get

        assertEquals(0xFFF1F2F3.toInt(), AdTheme.nativeBackground(appColorProvider = provider))
        assertEquals(0xFFF1F2F3.toInt(), AdTheme.bannerBackground(appColorProvider = provider))
        assertEquals(0xFFF1F2F3.toInt(), AdTheme.cardStyle("native", appColorProvider = provider).backgroundColor)
        assertEquals(0xFFF1F2F3.toInt(), AdTheme.cardStyle("banner", appColorProvider = provider).backgroundColor)
        assertEquals(0xFF111213.toInt(), AdTheme.nativeText(fallback = 0, appColorProvider = provider))
        assertEquals(0xFF111213.toInt(), AdTheme.bannerText(fallback = 0, appColorProvider = provider))
        assertEquals(0xFF454647.toInt(), AdTheme.nativeBody(fallback = 0, appColorProvider = provider))
        assertEquals(0xFF454647.toInt(), AdTheme.nativeMeta(fallback = 0, appColorProvider = provider))
        assertEquals(0xFF454647.toInt(), AdTheme.bannerSecondary(fallback = 0, appColorProvider = provider))
    }

    @Test
    fun shimmerUsesAdminMediaShimmerColorsAndSafeDefaults() {
        val appColors = mapOf(
            "media_shimmer_base_color" to "#102030",
            "media_shimmer_highlight_color" to "#A0B0C0",
            "primary" to "#010203",
        )
        assertEquals(0xFF102030.toInt(), AdTheme.shimmerBaseColor(appColors::get))
        assertEquals(0xFFA0B0C0.toInt(), AdTheme.shimmerHighlightColor(appColors::get))
        assertEquals(0xFF404040.toInt(), AdTheme.shimmerBaseColor { null })
        assertEquals(0xFFFFFFFF.toInt(), AdTheme.shimmerHighlightColor { null })
    }

    @Test
    fun cardPresentationUsesFormatOverridesThenGenericFallbackAndSafeRadiusPresets() {
        val appColors = mapOf(
            "native_card_enabled" to "false",
            "banner_card_enabled" to "false",
            "ad_card_enabled" to "false",
            "native_card_background_color" to "#112233",
            "banner_card_background_color" to "invalid",
            "ad_card_background_color" to "#445566",
            "native_card_corner_radius" to "extra_large",
            "ad_card_corner_radius" to "medium",
        )
        val provider: (String) -> String? = appColors::get
        val native = AdTheme.cardStyle("native", appColorProvider = provider)
        val banner = AdTheme.cardStyle("banner", appColorProvider = provider)

        assertEquals(0xFF112233.toInt(), native.backgroundColor)
        assertEquals(24f, native.cornerRadiusDp)
        assertEquals(0xFF445566.toInt(), banner.backgroundColor)
        assertEquals(12f, banner.cornerRadiusDp)
        assertEquals(0f, AdTheme.radiusDp("-100"))
        assertEquals(0f, AdTheme.radiusDp("junk"))
        assertEquals(0f, AdTheme.radiusDp("none"))
        assertEquals(0f, AdTheme.radiusDp("999999"))
        assertEquals(0x00000000, AdTheme.cardStyle("native", appColorProvider = { null }).backgroundColor)
        assertEquals(0f, AdTheme.cardStyle("native", appColorProvider = { null }).cornerRadiusDp)
        assertEquals(5f, AdTheme.cardStyle("native", appColorProvider = { null }).elevationDp)
        assertEquals(true, native.enabled)
    }

    @Test
    fun cardSurfaceOptionsResolvePerFormatThenSharedAndUseVisibleDefaults() {
        val colors = mapOf(
            "native_card_enabled" to "false",
            "banner_card_enabled" to "false",
            "native_card_elevation" to "medium",
            "ad_card_elevation" to "strong",
            "banner_card_elevation" to "not-a-preset",
            "ad_card_border_color" to "#123456",
            "native_card_border_width" to "thin",
            "ad_card_border_width" to "medium",
            "native_card_padding" to "comfortable",
            "banner_card_padding" to "compact",
        )
        val provider: (String) -> String? = colors::get
        val native = AdTheme.cardStyle("native", appColorProvider = provider)
        val banner = AdTheme.cardStyle("banner", appColorProvider = provider)
        val defaults = AdTheme.cardStyle("native", appColorProvider = { null })

        assertEquals(6f, native.elevationDp)
        assertEquals(1f, native.borderWidthDp)
        assertEquals(0xFF123456.toInt(), native.borderColor)
        assertEquals(12f, native.innerPaddingDp)
        assertEquals(10f, banner.elevationDp)
        assertEquals(2f, banner.borderWidthDp)
        assertEquals(8f, banner.innerPaddingDp)
        assertEquals(5f, defaults.elevationDp)
        assertEquals(0f, defaults.cornerRadiusDp)
        assertEquals(0f, defaults.borderWidthDp)
        assertEquals(true, defaults.enabled)
        assertEquals(true, defaults.explicitlyConfigured)
        assertEquals(0f, AdTheme.elevationDp("-1"))
        assertEquals(0f, AdTheme.elevationDp("9999"))
        assertEquals(0f, AdTheme.elevationDp("junk"))
    }

    @Test
    fun configuredAdBackgroundFallbackControlsCardWhenNoCardSpecificColorExists() {
        val legacyAppColors = mapOf(
            "native_background_color" to "#040404",
            "banner_background_color" to "#0B0B0B",
        )
        val provider: (String) -> String? = legacyAppColors::get

        assertEquals(
            0xFF040404.toInt(),
            AdTheme.cardStyle("native", mapOf("native_transparent_background" to false), provider).backgroundColor,
        )
        assertEquals(
            0xFF0B0B0B.toInt(),
            AdTheme.cardStyle("banner", mapOf("banner_transparent_background" to false), provider).backgroundColor,
        )
        assertEquals(
            0xFFABCDEF.toInt(),
            AdTheme.cardStyle(
                "native",
                mapOf("native_transparent_background" to false, "native_card_background_color" to "#ABCDEF"),
                provider,
            ).backgroundColor,
        )
        assertEquals(
            0xFF123456.toInt(),
            AdTheme.cardStyle(
                "native",
                mapOf("native_transparent_background" to false, "ad_card_background_color" to "#123456"),
                { key -> if (key == "native_background_color") "#040404" else null },
            ).backgroundColor,
        )
        assertEquals(
            0xFF040404.toInt(),
            AdTheme.cardStyle("native", mapOf("native_transparent_background" to true), provider).backgroundColor,
        )
        assertEquals(
            0xFF040404.toInt(),
            AdTheme.cardStyle("native", appColorProvider = provider).backgroundColor,
        )
    }

    @Test
    fun adBackgroundFallbackColorsAreAppliedToAllMatchingCardsBelowCardOverrides() {
        val colors = mapOf(
            "native_background_color" to "#123456",
            "banner_background_color" to "#234567",
            "ad_background_color" to "#345678",
        )
        val provider: (String) -> String? = colors::get

        assertEquals(0xFF123456.toInt(), AdTheme.cardStyle("native", appColorProvider = provider).backgroundColor)
        assertEquals(0xFF234567.toInt(), AdTheme.cardStyle("banner", appColorProvider = provider).backgroundColor)
        assertEquals(
            0xFFABCDEF.toInt(),
            AdTheme.cardStyle(
                "native",
                mapOf("native_card_background_color" to "#ABCDEF", "native_background_color" to "#123456"),
                provider,
            ).backgroundColor,
        )

        val sharedFallback: (String) -> String? = { key -> if (key == "ad_background_color") "#456789" else null }
        assertEquals(0xFF456789.toInt(), AdTheme.cardStyle("native", appColorProvider = sharedFallback).backgroundColor)
        assertEquals(0xFF456789.toInt(), AdTheme.cardStyle("banner", appColorProvider = sharedFallback).backgroundColor)
    }
}
