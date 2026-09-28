package com.itwingtech.itwingsdk.ads

/** Pure final gate shared by automatic and explicit App Open presentation requests. */
internal object AppOpenPresentationPolicy {
    fun mayPresentFirstEverLaunch(hasCompletedPreviousSession: Boolean): Boolean = hasCompletedPreviousSession

    fun shouldAutomaticallyPresentResume(hasEnteredForegroundBefore: Boolean, backgroundDurationMs: Long): Boolean =
        hasEnteredForegroundBefore && backgroundDurationMs >= MINIMUM_RESUME_BACKGROUND_MS

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

    private const val MINIMUM_RESUME_BACKGROUND_MS = 2_000L
}
