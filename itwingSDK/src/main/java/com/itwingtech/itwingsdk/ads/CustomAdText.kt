package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.CustomAdConfig

internal data class CustomAdText(val advertiser: String?, val headline: String?)

internal fun CustomAdConfig.displayText(): CustomAdText {
    val advertiser = sequenceOf(
        (metadata["brand"] as? Map<*, *>)?.get("name") as? String,
        metadata["advertiser"] as? String,
        metadata["brand_name"] as? String,
        campaignGroup,
    ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()
    val headline = sequenceOf(
        headline,
        metadata["product_title"] as? String,
        metadata["title"] as? String,
        name,
    ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull { candidate -> advertiser == null || !candidate.equals(advertiser, ignoreCase = true) }
    return CustomAdText(advertiser, headline)
}
