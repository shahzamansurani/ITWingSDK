package com.itwingtech.itwingsdk.ads

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import androidx.cardview.widget.CardView
import com.facebook.shimmer.Shimmer
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.card.MaterialCardView
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.StartupTrace

/** Normalizes the admin app.colors contract for every SDK-rendered ad surface. */
internal object AdTheme {
    data class AdCardStyle(
        val backgroundColor: Int,
        val cornerRadiusDp: Float,
        val elevationDp: Float = 0f,
        val borderColor: Int? = null,
        val borderWidthDp: Float = 0f,
        val innerPaddingDp: Float = 0f,
        val enabled: Boolean = true,
        val backgroundConfigured: Boolean = false,
        val explicitlyConfigured: Boolean = false,
        val cardSettingsConfigured: Boolean = false,
    )

    fun cardStyle(
        format: String,
        metadata: Map<String, Any?>? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): AdCardStyle {
        val native = format.equals("native", ignoreCase = true)
        val formatBackgroundKey = if (native) "native_card_background_color" else "banner_card_background_color"
        val radiusKeys = if (native) listOf("native_card_corner_radius", "ad_card_corner_radius") else listOf("banner_card_corner_radius", "ad_card_corner_radius")
        val fallbackBackgroundKeys = if (native) {
            listOf("native_background_color", "banner_background_color", "ad_background_color", "surface_color", "background_color")
        } else {
            listOf("banner_background_color", "ad_background_color", "surface_color", "background_color")
        }
        // The placement editor's legacy native_background_color defaults to
        // black even when native_transparent_background is enabled. In that
        // mode it is explicitly not the surface color; the app-level SDK
        // palette must control the approved MaterialCardView instead.
        val useLegacyPlacementBackground = !native ||
            metadata?.get("native_transparent_background")?.toString()?.toBooleanStrictOrNull() != true
        val configuredCardBackground = sequence {
            yield(metadata?.get(formatBackgroundKey) as? String)
            yield(appColorProvider(formatBackgroundKey))
            yield(metadata?.get("ad_card_background_color") as? String)
            yield(appColorProvider("ad_card_background_color"))
            // The admin's ad background fallback controls the actual outer card
            // when no card-specific color is supplied. Previously this setting
            // only painted the legacy inner layout, leaving the Material card
            // on its default white surface.
            fallbackBackgroundKeys.forEach { key ->
                if (useLegacyPlacementBackground) yield(metadata?.get(key) as? String)
                yield(appColorProvider(key))
            }
        }.filterNotNull().firstNotNullOfOrNull(::parseCardColor)
        // Card presentation is unconditional. Legacy *_card_enabled values are
        // intentionally ignored; administrators configure appearance only.
        val enabled = true
        val configuredRadius = radiusKeys.firstNotNullOfOrNull { key ->
            sequenceOf(
                metadata?.get(key)?.toString(),
                appColorProvider(key)
            ).firstNotNullOfOrNull { radiusDp(it, -1f).takeIf { parsed -> parsed >= 0f } }
        }
        fun tokens(specific: String, generic: String): List<String?> = sequenceOf(
            metadata?.get(specific)?.toString(), appColorProvider(specific),
            metadata?.get(generic)?.toString(), appColorProvider(generic),
        ).filter { !it.isNullOrBlank() }.toList()
        val borderKeys = if (native) "native_card_border_color" to "ad_card_border_color" else "banner_card_border_color" to "ad_card_border_color"
        val borderWidthKeys = if (native) "native_card_border_width" to "ad_card_border_width" else "banner_card_border_width" to "ad_card_border_width"
        val configuredElevation = dimensionPresetOrNull(tokens(if (native) "native_card_elevation" else "banner_card_elevation", "ad_card_elevation"), mapOf("none" to 0f, "subtle" to 2f, "medium" to 6f, "strong" to 10f), 16f)
        val borderColor = sequenceOf(
            metadata?.get(borderKeys.first) as? String,
            appColorProvider(borderKeys.first),
            metadata?.get(borderKeys.second) as? String,
            appColorProvider(borderKeys.second)
        )
            .filterNotNull().firstNotNullOfOrNull(::parseCardColor)

        val configuredBorderWidth = dimensionPresetOrNull(tokens(borderWidthKeys.first, borderWidthKeys.second), mapOf("none" to 0f, "thin" to 1f, "medium" to 2f), 4f)
        val configuredPadding = dimensionPresetOrNull(tokens(if (native) "native_card_padding" else "banner_card_padding", "ad_card_padding"), mapOf("none" to 0f, "compact" to 8f, "comfortable" to 12f), 24f)
        val styleKeys = listOf(
            formatBackgroundKey, "ad_card_background_color", radiusKeys.first(), radiusKeys.last(),
            if (native) "native_card_elevation" else "banner_card_elevation", "ad_card_elevation",
            borderKeys.first, borderKeys.second, borderWidthKeys.first, borderWidthKeys.second,
            if (native) "native_card_padding" else "banner_card_padding", "ad_card_padding",
            if (native) "native_stroke_color" else "banner_stroke_color", "ad_stroke_color", "stroke_color",
        )
        val legacySurfaceKeys = listOf(
            if (native) "native_stroke_color" else "banner_stroke_color",
            "ad_stroke_color",
            "stroke_color",
        )
        fun isConfigured(key: String): Boolean {
            fun configured(value: String?): Boolean =
                !value.isNullOrBlank() && !value.equals("inherit", ignoreCase = true) && !value.equals("default", ignoreCase = true)
            return configured(metadata?.get(key)?.toString()) || configured(appColorProvider(key))
        }
        val cardSettingsConfigured = styleKeys.any { it !in legacySurfaceKeys && isConfigured(it) }
        val activeCard = enabled
        val background = configuredCardBackground ?: Color.TRANSPARENT
        val radius = configuredRadius ?: DEFAULT_CARD_RADIUS_DP
        val elevation = configuredElevation ?: DEFAULT_CARD_ELEVATION_DP
        val borderWidth = configuredBorderWidth ?: DEFAULT_CARD_BORDER_WIDTH_DP
        // A small host inset keeps the radius/background visible around an
        // unmodified Google Banner AdView. It is outside the creative, and is
        // entirely absent when card mode is disabled.
        val defaultHostInset = if (!native && activeCard) 4f else 0f
        val padding = if (!activeCard) 0f else configuredPadding ?: defaultHostInset
        return AdCardStyle(
            backgroundColor = background,
            cornerRadiusDp = radius,
            elevationDp = elevation,
            borderColor = if (activeCard) borderColor else null,
            borderWidthDp = borderWidth,
            innerPaddingDp = padding,
            enabled = activeCard,
            backgroundConfigured = configuredCardBackground != null,
            explicitlyConfigured = true,
            cardSettingsConfigured = cardSettingsConfigured,
        )
    }

    fun radiusDp(value: String?, fallback: Float = DEFAULT_CARD_RADIUS_DP): Float {
        val normalized = value?.trim()?.lowercase() ?: return fallback
        val preset = when (normalized) {
            "none" -> 0f
            "small" -> 8f
            "medium" -> 12f
            "large" -> 16f
            "extra_large", "extra large", "xlarge" -> 24f
            else -> normalized.toFloatOrNull()?.takeIf { it in 0f..32f }
        }
        return preset ?: fallback
    }

    fun applyCard(view: View, format: String, metadata: Map<String, Any?>? = null, strokeColor: Int? = null, clipContent: Boolean = true) {
        val style = cardStyle(format, metadata)
        val density = view.resources.displayMetrics.density
        val radius = style.cornerRadiusDp * density
        val stroke = style.borderColor ?: strokeColor
        val strokeWidth = if (!style.enabled) 0f else if (style.borderWidthDp > 0f) style.borderWidthDp else if (style.borderColor == null && strokeColor != null) 1f else 0f
        val backgroundColor = if (style.enabled) {
            if (!style.backgroundConfigured) themeSurfaceColor(view) else style.backgroundColor
        } else Color.TRANSPARENT
        StartupTrace.event(
            view.context,
            "AD_THEME",
            "format=${format.take(16)} view=${view.javaClass.simpleName.take(32)} " +
                "bgConfigured=${style.backgroundConfigured} bg=${colorHex(backgroundColor)} " +
                "appNativeBg=${debugColor(ITWingSDK.getConfiguredColor("native_background_color"))} " +
                "appBannerBg=${debugColor(ITWingSDK.getConfiguredColor("banner_background_color"))} " +
                "appPrimary=${debugColor(ITWingSDK.getConfiguredColor("primary"))} " +
                "appText=${debugColor(ITWingSDK.getConfiguredColor("${format.lowercase()}_text_color"))} " +
                "metaCardBg=${debugColor(metadata?.get(if (format.equals("native", true)) "native_card_background_color" else "banner_card_background_color") as? String)} " +
                "metaBg=${debugColor(metadata?.get(if (format.equals("native", true)) "native_background_color" else "banner_background_color") as? String)} " +
                "radiusDp=${style.cornerRadiusDp} elevationDp=${style.elevationDp} " +
                "paddingDp=${style.innerPaddingDp} cardSettings=${style.cardSettingsConfigured}",
        )
        val material = view as? MaterialCardView
        val cardView = view as? CardView
        val originalPadding = view.getTag(com.itwingtech.itwingsdk.R.id.ad_card_original_padding) as? IntArray
            ?: (cardView?.let {
                intArrayOf(it.contentPaddingLeft, it.contentPaddingTop, it.contentPaddingRight, it.contentPaddingBottom)
            } ?: intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)).also {
                view.setTag(com.itwingtech.itwingsdk.R.id.ad_card_original_padding, it)
            }
        val padding = (style.innerPaddingDp * density).toInt()
        if (material != null) {
            material.setCardBackgroundColor(backgroundColor)
            material.radius = radius
            material.cardElevation = style.elevationDp * density
            material.maxCardElevation = style.elevationDp * density
            material.useCompatPadding = style.enabled && style.elevationDp > 0f
            material.preventCornerOverlap = false
            material.setStrokeWidth((strokeWidth * density).toInt())
            material.setStrokeColor(stroke ?: android.graphics.Color.TRANSPARENT)
            material.setContentPadding(originalPadding[0] + padding, originalPadding[1] + padding, originalPadding[2] + padding, originalPadding[3] + padding)
        } else if (cardView != null) {
            cardView.setCardBackgroundColor(backgroundColor)
            cardView.radius = radius
            cardView.cardElevation = style.elevationDp * density
            cardView.maxCardElevation = style.elevationDp * density
            cardView.useCompatPadding = style.enabled && style.elevationDp > 0f
            cardView.preventCornerOverlap = false
            cardView.foreground = if (stroke != null && strokeWidth > 0f) {
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Color.TRANSPARENT)
                    setStroke((strokeWidth * density).toInt().coerceAtLeast(1), stroke)
                }
            } else null
            cardView.setContentPadding(originalPadding[0] + padding, originalPadding[1] + padding, originalPadding[2] + padding, originalPadding[3] + padding)
        } else {
            view.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(backgroundColor)
                if (stroke != null && strokeWidth > 0f) setStroke((strokeWidth * density).toInt().coerceAtLeast(1), stroke)
            }
            view.elevation = style.elevationDp * density
            view.setPadding(originalPadding[0] + padding, originalPadding[1] + padding, originalPadding[2] + padding, originalPadding[3] + padding)
        }
        view.clipToOutline = style.enabled && clipContent
        ensureShadowRoom(view, style.elevationDp, density)
    }

    /** Installs the one real Material card host shared by real and custom ad renderers. */
    fun attachCardSurface(
        container: ViewGroup,
        content: View,
        format: String,
        metadata: Map<String, Any?>? = null,
        clipContent: Boolean = true,
        legacyStroke: Int? = null,
    ) {
        val style = cardStyle(format, metadata)
        if (!style.explicitlyConfigured) {
            // Preserve the v1.47 host/layout hierarchy and sizing unless an
            // administrator explicitly opts into a card appearance.
            if (content.parent !== container) (content.parent as? ViewGroup)?.removeView(content)
            container.removeAllViews()
            container.addView(content)
            return
        }

        val cardHost = (container as? CardView)
            ?: (container.parent as? CardView)
            ?: (content as? CardView)
        if (cardHost != null) {
            applyCard(cardHost, format, metadata, legacyStroke, clipContent)
            if (content.parent !== container) (content.parent as? ViewGroup)?.removeView(content)
            container.removeAllViews()
            // The inflated ad template already carries its v1.47 wrap/content
            // dimensions. Re-adding it as MATCH_PARENT made opt-in cards fill
            // the entire host (and, on full-size hosts, the screen).
            container.addView(content)
            return
        }

        // MaterialCardView is a genuine elevated presentation surface; its
        // neutral defaults preserve the original layout unless configured.
        val card = MaterialCardView(
            ContextThemeWrapper(container.context, com.itwingtech.itwingsdk.R.style.ITWingAdCardTheme)
        ).apply {
            useCompatPadding = false
            preventCornerOverlap = false
            setCardBackgroundColor(Color.TRANSPARENT)
            radius = 0f
            cardElevation = 0f
            maxCardElevation = 0f
            strokeWidth = 0
            isClickable = false
            isFocusable = false
        }
        val sourceParams = content.layoutParams
        val cardParams = sourceParams?.let { params ->
            ViewGroup.MarginLayoutParams(params.width, params.height).also { margins ->
                (params as? ViewGroup.MarginLayoutParams)?.let {
                    margins.setMargins(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
                }
            }
        } ?: ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        applyCard(card, format, metadata, legacyStroke, clipContent)
        if (content.parent is ViewGroup) (content.parent as ViewGroup).removeView(content)
        container.removeAllViews()
        container.addView(card, cardParams)
        card.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sourceParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT))
        // Apply again after attachment so ancestor clipping is handled as well as
        // the card's own elevation and geometry.
        applyCard(card, format, metadata, legacyStroke, clipContent)
    }

    private fun dimensionPreset(values: List<String?>, presets: Map<String, Float>, min: Float, max: Float): Float {
        for (value in values) {
            val normalized = value?.trim()?.lowercase() ?: continue
            presets[normalized]?.let { return it }
            normalized.removeSuffix("dp").toFloatOrNull()?.takeIf { it in min..max }?.let { return it }
        }
        return min
    }

    private fun dimensionPresetOrNull(values: List<String?>, presets: Map<String, Float>, max: Float): Float? {
        for (value in values) {
            val normalized = value?.trim()?.lowercase() ?: continue
            presets[normalized]?.let { return it }
            normalized.removeSuffix("dp").toFloatOrNull()?.takeIf { it in 0f..max }?.let { return it }
        }
        return null
    }

    private fun themeSurfaceColor(view: View): Int {
        val dark = (view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val fallback = if (dark) 0xFF1E1E1E.toInt() else Color.WHITE
        return runCatching {
            com.google.android.material.color.MaterialColors.getColor(
                view,
                com.google.android.material.R.attr.colorSurface,
                fallback,
            )
        }.getOrDefault(fallback)
    }

    fun elevationDp(value: String?): Float = dimensionPreset(
        listOf(value), mapOf("none" to 0f, "subtle" to 2f, "medium" to 6f, "strong" to 10f), 0f, 16f,
    )

    private fun ensureShadowRoom(view: View, elevationDp: Float, density: Float) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            val original = view.getTag(com.itwingtech.itwingsdk.R.id.ad_card_original_margins) as? IntArray
                ?: intArrayOf(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin).also {
                    view.setTag(com.itwingtech.itwingsdk.R.id.ad_card_original_margins, it)
                }
            val inset = if (elevationDp > 0f) (6f * density).toInt() else 0
            params.setMargins(original[0] + inset, original[1] + inset, original[2] + inset, original[3] + inset)
            view.layoutParams = params
        }
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            val saved = parent.getTag(com.itwingtech.itwingsdk.R.id.ad_card_original_parent_clipping) as? BooleanArray
            if (elevationDp > 0f) {
                if (saved == null) parent.setTag(com.itwingtech.itwingsdk.R.id.ad_card_original_parent_clipping, booleanArrayOf(parent.clipChildren, parent.clipToPadding))
                parent.clipChildren = false
                parent.clipToPadding = false
            } else if (saved != null) {
                parent.clipChildren = saved[0]
                parent.clipToPadding = saved[1]
                parent.setTag(com.itwingtech.itwingsdk.R.id.ad_card_original_parent_clipping, null)
            }
            parent = parent.parent as? ViewGroup
        }
    }

    fun color(
        fallback: Int,
        appKeys: List<String>,
        metadata: Map<String, Any?>? = null,
        metadataKeys: List<String> = emptyList(),
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int {
        val candidates = sequence {
            yieldAll(metadataKeys.mapNotNull { metadata?.get(it) as? String })
            yieldAll(appKeys.mapNotNull(appColorProvider))
        }
        for (candidate in candidates) {
            parseHex(candidate)?.let { return it }
        }
        return fallback
    }

    fun primaryColor(metadata: Map<String, Any?>? = null): String? = sequenceOf(
        metadata?.get("ad_primary_color") as? String,
        (metadata?.get("brand") as? Map<*, *>)?.get("primary_color") as? String,
        ITWingSDK.getConfiguredColor("primary"),
        ITWingSDK.getConfiguredColor("primary_color"),
        ITWingSDK.getConfiguredColor("accent"),
    ).firstNotNullOfOrNull { value ->
        value?.takeIf { parseHex(it) != null }
    }

    fun parseHex(value: String?): Int? = runCatching {
        val raw = value?.trim()?.removePrefix("#") ?: return null
        if (raw.isEmpty() || raw.any { it.digitToIntOrNull(16) == null }) return null
        val expanded = when (raw.length) {
            3 -> "FF" + raw.map { "$it$it" }.joinToString("")
            4 -> raw.map { "$it$it" }.joinToString("") // Android-style #ARGB
            6 -> "FF$raw"
            8 -> raw
            else -> return null
        }
        // Keep parsing pure JVM-safe so malformed/valid payload handling can be
        // regression-tested without Android framework stubs.
        expanded.toLong(16).toInt()
    }.getOrNull()

    private fun parseCardColor(value: String?): Int? = when (value?.trim()?.lowercase()) {
        "transparent", "none" -> Color.TRANSPARENT
        else -> parseHex(value)
    }

    fun safeColor(value: String?, fallback: Int): Int = parseHex(value) ?: fallback

    fun nativeCtaColor(
        metadata: Map<String, Any?>? = null,
        adFallback: String? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int = color(
        fallback = parseHex(adFallback) ?: 0xFF2563EB.toInt(),
        appKeys = listOf("native_cta_color", "native_cta_background_color", "ad_cta_color", "ad_cta_background_color", "primary", "primary_color", "accent"),
        metadata = metadata,
        metadataKeys = listOf("native_cta_color", "native_cta_background_color", "cta_background_color"),
        appColorProvider = appColorProvider,
    )

    fun nativeCtaTextColor(
        metadata: Map<String, Any?>? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int = color(
        fallback = Color.WHITE,
        appKeys = listOf("native_cta_text_color", "ad_cta_text_color", "cta_text_color"),
        metadata = metadata,
        metadataKeys = listOf("native_cta_text_color", "ad_cta_text_color", "cta_text_color"),
        appColorProvider = appColorProvider,
    )

    fun bannerCtaColor(
        metadata: Map<String, Any?>? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int = color(
        fallback = 0xFF2563EB.toInt(),
        appKeys = listOf("banner_cta_color", "banner_cta_background_color", "ad_cta_color", "ad_cta_background_color", "primary", "primary_color", "accent"),
        metadata = metadata,
        metadataKeys = listOf("banner_cta_color", "banner_cta_background_color", "cta_background_color"),
        appColorProvider = appColorProvider,
    )

    fun nativeLabelColor(
        metadata: Map<String, Any?>? = null,
        adFallback: String? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int = color(
        fallback = parseHex(adFallback) ?: 0xFF2563EB.toInt(),
        appKeys = listOf("native_ad_label_background_color", "native_ad_label_color", "ad_label_background_color", "ad_badge_background_color", "primary", "primary_color", "accent"),
        metadata = metadata,
        metadataKeys = listOf("native_ad_label_background_color", "native_ad_label_color", "ad_label_background_color", "ad_badge_background_color"),
        appColorProvider = appColorProvider,
    )

    fun nativeLabelTextColor(
        metadata: Map<String, Any?>? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int = color(
        fallback = Color.WHITE,
        appKeys = listOf("native_ad_label_text_color", "ad_label_text_color", "ad_badge_text_color"),
        metadata = metadata,
        metadataKeys = listOf("native_ad_label_text_color", "ad_label_text_color", "ad_badge_text_color"),
        appColorProvider = appColorProvider,
    )

    fun nativeBackground(
        metadata: Map<String, Any?>? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int = color(
        fallback = Color.TRANSPARENT,
        appKeys = listOf("native_background_color", "banner_background_color", "ad_background_color", "surface_color", "background_color"),
        metadata = metadata,
        metadataKeys = listOf("native_background_color", "banner_background_color", "ad_background_color", "background_color"),
        appColorProvider = appColorProvider,
    )

    fun nativeStroke(
        metadata: Map<String, Any?>? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int? = firstConfiguredColor(
        metadata,
        listOf("native_stroke_color", "stroke_color"),
        listOf("native_stroke_color", "ad_stroke_color", "stroke_color"),
        appColorProvider,
    )

    fun bannerBackground(
        metadata: Map<String, Any?>? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int? = firstConfiguredColor(
        metadata,
        listOf("banner_background_color", "background_color"),
        listOf("banner_background_color", "ad_background_color", "surface_color", "background_color"),
        appColorProvider,
    )

    fun bannerStroke(
        metadata: Map<String, Any?>? = null,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int? = firstConfiguredColor(
        metadata,
        listOf("banner_stroke_color", "stroke_color"),
        listOf("banner_stroke_color", "ad_stroke_color", "stroke_color"),
        appColorProvider,
    )

    fun styleCard(view: View, fillColor: Int?, strokeColor: Int?, cornerRadiusPx: Float) {
        val drawable = (view.background?.mutate() as? GradientDrawable)
            ?: GradientDrawable().apply { cornerRadius = cornerRadiusPx }
        drawable.cornerRadius = cornerRadiusPx
        if (fillColor != null) drawable.setColor(fillColor)
        if (strokeColor != null) drawable.setStroke((view.resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
        view.background = drawable
        view.clipToOutline = true
    }

    private fun firstConfiguredColor(
        metadata: Map<String, Any?>?,
        metadataKeys: List<String>,
        appKeys: List<String>,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int? {
        val values = sequence {
            yieldAll(metadataKeys.mapNotNull { metadata?.get(it) as? String })
            yieldAll(appKeys.mapNotNull(appColorProvider))
        }
        return values.firstNotNullOfOrNull(::parseHex)
    }

    fun nativeText(metadata: Map<String, Any?>? = null, fallback: Int, appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor): Int = color(
        fallback = fallback,
        appKeys = listOf("native_text_color", "native_headline_text_color", "headline_text_color", "banner_text_color", "text_color"),
        metadata = metadata,
        metadataKeys = listOf("native_headline_text_color", "native_text_color", "headline_text_color", "banner_text_color", "text_color"),
        appColorProvider = appColorProvider,
    )

    fun nativeBody(metadata: Map<String, Any?>? = null, fallback: Int, appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor): Int = color(
        fallback = fallback,
        appKeys = listOf("native_body_text_color", "native_secondary_text_color", "body_text_color", "secondary_text_color", "native_text_color"),
        metadata = metadata,
        metadataKeys = listOf("native_body_text_color", "native_secondary_text_color", "body_text_color", "secondary_text_color"),
        appColorProvider = appColorProvider,
    )

    fun nativeMeta(metadata: Map<String, Any?>? = null, fallback: Int, appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor): Int = color(
        fallback = fallback,
        appKeys = listOf("native_meta_text_color", "native_secondary_text_color", "meta_text_color", "secondary_text_color", "native_text_color"),
        metadata = metadata,
        metadataKeys = listOf("native_meta_text_color", "native_secondary_text_color", "meta_text_color", "secondary_text_color"),
        appColorProvider = appColorProvider,
    )

    fun bannerText(metadata: Map<String, Any?>? = null, fallback: Int, appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor): Int = color(
        fallback = fallback,
        appKeys = listOf("banner_text_color", "native_text_color", "text_color"),
        metadata = metadata,
        metadataKeys = listOf("banner_text_color", "text_color"),
        appColorProvider = appColorProvider,
    )

    fun bannerSecondary(
        metadata: Map<String, Any?>? = null,
        fallback: Int,
        appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor,
    ): Int = color(
        fallback = fallback,
        appKeys = listOf("native_secondary_text_color", "native_meta_text_color", "banner_text_color", "secondary_text_color", "text_color"),
        metadata = metadata,
        metadataKeys = listOf("native_secondary_text_color", "native_meta_text_color", "banner_text_color", "secondary_text_color", "text_color"),
        appColorProvider = appColorProvider,
    )

    fun bannerCtaText(metadata: Map<String, Any?>? = null, appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor): Int = color(
        fallback = Color.WHITE,
        appKeys = listOf("banner_cta_text_color", "native_cta_text_color", "cta_text_color"),
        metadata = metadata,
        metadataKeys = listOf("banner_cta_text_color", "native_cta_text_color", "cta_text_color"),
        appColorProvider = appColorProvider,
    )

    fun bannerLabelColor(metadata: Map<String, Any?>? = null, adFallback: String? = null, appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor): Int = color(
        fallback = parseHex(adFallback) ?: 0xFF2563EB.toInt(),
        appKeys = listOf("native_ad_label_background_color", "ad_label_background_color", "primary", "primary_color", "accent"),
        metadata = metadata,
        metadataKeys = listOf("native_ad_label_background_color", "ad_label_background_color"),
        appColorProvider = appColorProvider,
    )

    fun bannerLabelTextColor(metadata: Map<String, Any?>? = null, appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor): Int = color(
        fallback = Color.WHITE,
        appKeys = listOf("native_ad_label_text_color", "ad_label_text_color"),
        metadata = metadata,
        metadataKeys = listOf("native_ad_label_text_color", "ad_label_text_color"),
        appColorProvider = appColorProvider,
    )

    fun styleShimmer(view: View?) {
        val shimmerView = view as? ShimmerFrameLayout ?: return
        val configuredBase = ITWingSDK.getConfiguredColor("media_shimmer_base_color")
            ?.takeIf { it.isNotBlank() }
        val configuredHighlight = ITWingSDK.getConfiguredColor("media_shimmer_highlight_color")
            ?.takeIf { it.isNotBlank() }
        // Leave the designed XML shimmer untouched unless both admin values
        // exist; SDK fallback colors must not silently replace its defaults.
        if (configuredBase == null || configuredHighlight == null) return
        shimmerView.setShimmer(
            Shimmer.ColorHighlightBuilder()
                .setBaseColor(parseHex(configuredBase) ?: return)
                .setHighlightColor(parseHex(configuredHighlight) ?: return)
                .setDuration(1100)
                .build()
        )
    }

    private fun debugColor(value: String?): String = value
        ?.trim()
        ?.takeIf { it.matches(Regex("(?i)(#[0-9a-f]{3,8}|transparent|none)")) }
        ?: "none"

    private fun colorHex(value: Int): String = "#%08X".format(value)

    fun shimmerBaseColor(appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor): Int = color(
        fallback = 0xFF404040.toInt(),
        appKeys = listOf("media_shimmer_base_color"),
        appColorProvider = appColorProvider,
    )

    fun shimmerHighlightColor(appColorProvider: (String) -> String? = ITWingSDK::getConfiguredColor): Int = color(
        fallback = Color.WHITE,
        appKeys = listOf("media_shimmer_highlight_color", "primary", "primary_color", "accent"),
        appColorProvider = appColorProvider,
    )
}

private const val DEFAULT_CARD_RADIUS_DP: Float = 0f
private const val DEFAULT_CARD_ELEVATION_DP: Float = 5f
private const val DEFAULT_CARD_BORDER_WIDTH_DP: Float = 0f
