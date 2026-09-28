package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.CustomAdConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAdMediaTest {
    @Test
    fun videoUrlAloneIsRecognizedAndPreferredOverPosterImage() {
        val ad = CustomAdConfig(
            imageUrl = "https://cdn.example.test/poster.jpg",
            videoUrl = "https://cdn.example.test/spot.mp4",
        )

        assertTrue(ad.isVideoMedia())
        assertEquals("https://cdn.example.test/spot.mp4", ad.resolvedMediaUrl())
    }

    @Test
    fun playableMediaUrlWinsOverDifferentLegacyVideoUrl() {
        val ad = CustomAdConfig(
            mediaUrl = "https://cdn.example.test/small-ad.mp4",
            videoUrl = "https://cdn.example.test/legacy-or-poster.jpg",
            mediaType = "video",
        )

        assertTrue(ad.isVideoMedia())
        assertEquals("https://cdn.example.test/small-ad.mp4", ad.resolvedMediaUrl())
    }

    @Test
    fun extensionlessTypedVideoMediaUrlWinsOverLegacyImageVideoUrl() {
        val ad = CustomAdConfig(
            mediaUrl = "https://cdn.example.test/play?id=small",
            videoUrl = "https://cdn.example.test/legacy.jpg",
            mediaType = "video/mp4",
        )

        assertTrue(ad.isVideoMedia())
        assertEquals("https://cdn.example.test/play?id=small", ad.resolvedMediaUrl())
    }

    @Test
    fun mimeTypeVideoValuesAreRecognized() {
        val ad = CustomAdConfig(
            mediaUrl = "https://cdn.example.test/stream",
            mediaType = "video/mp4",
        )

        assertTrue(ad.isVideoMedia())
        assertEquals("https://cdn.example.test/stream", ad.resolvedMediaUrl())
    }

    @Test
    fun metadataVideoUrlIsUsedWhenTopLevelVideoUrlIsMissing() {
        val ad = CustomAdConfig(
            mediaUrl = "https://cdn.example.test/poster.jpg",
            metadata = mapOf("video_url" to "https://cdn.example.test/spot.m3u8"),
        )

        assertTrue(ad.isVideoMedia())
        assertEquals("https://cdn.example.test/spot.m3u8", ad.resolvedMediaUrl())
    }

    @Test
    fun metadataMimeTypeAndExtensionlessVideoUrlAreRecognizedConsistently() {
        val ad = CustomAdConfig(
            imageUrl = "https://cdn.example.test/poster.jpg",
            metadata = mapOf(
                "media_type" to "video/mp4",
                "video_url" to "https://cdn.example.test/stream?id=campaign-7",
            ),
        )

        assertTrue(ad.isVideoMedia())
        assertEquals("https://cdn.example.test/stream?id=campaign-7", ad.resolvedMediaUrl())
    }

    @Test
    fun videoMimeTypeIsNotLimitedToTheLiteralVideoToken() {
        val ad = CustomAdConfig(
            mediaUrl = "https://cdn.example.test/stream?id=campaign-8",
            mediaType = "VIDEO/MP4",
        )

        assertTrue(ad.isVideoMedia())
        assertEquals("https://cdn.example.test/stream?id=campaign-8", ad.resolvedMediaUrl())
    }

    @Test
    fun videoFileExtensionsAreRecognizedWithQueryStrings() {
        val ad = CustomAdConfig(mediaUrl = "https://cdn.example.test/clip.webm?token=temporary")

        assertTrue(ad.isVideoMedia())
        assertEquals("https://cdn.example.test/clip.webm?token=temporary", ad.resolvedMediaUrl())
    }

    @Test
    fun imageCampaignKeepsExistingMediaPrecedence() {
        val ad = CustomAdConfig(
            imageUrl = "https://cdn.example.test/image.jpg",
            mediaUrl = "https://cdn.example.test/creative.jpg",
            mediaType = "image",
        )

        assertFalse(ad.isVideoMedia())
        assertEquals("https://cdn.example.test/creative.jpg", ad.resolvedMediaUrl())
    }
}
