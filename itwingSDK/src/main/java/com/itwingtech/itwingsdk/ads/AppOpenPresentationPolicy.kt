package com.itwingtech.itwingsdk.ads

internal object AppOpenPresentationPolicy {
    fun mayPresentFirstEverLaunch(hasCompletedPreviousSession: Boolean) = hasCompletedPreviousSession

    fun shouldAutomaticallyPresentResume(
        hasEnteredForegroundBefore: Boolean,
        backgroundDurationMs: Long,
    ) = hasEnteredForegroundBefore && backgroundDurationMs >= 2_000L

    fun rejectionReason(
        processForeground: Boolean,
        activityResumedAndUsable: Boolean,
        foregroundSessionMatches: Boolean,
        fullscreenConflict: Boolean,
        firstEverLaunch: Boolean = false,
        ownsFullscreenSlot: Boolean = false,
    ): String? = when {
        firstEverLaunch -> "SKIPPED_FIRST_EVER_LAUNCH"
        !processForeground -> "SKIPPED_BACKGROUND"
        !foregroundSessionMatches -> "SKIPPED_STALE_FOREGROUND_SESSION"
        !activityResumedAndUsable -> "SKIPPED_NO_RESUMED_ACTIVITY"
        fullscreenConflict && !ownsFullscreenSlot -> "SKIPPED_FULLSCREEN_CONFLICT"
        else -> null
    }
}
