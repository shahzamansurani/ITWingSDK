# IT Wing SDK Android example

This project contains the current XML-first example app and the SDK module. The example app is intentionally a reference integration: host code supplies placement names and the SDK resolves the configured ad format.

## Gradle dependency

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.shahzamansurani:ITWingSDK:v1.63")
}
```

## Initialization

The example uses the public startup flow from `SplashActivity`:

```kotlin
ITWingSDK.startAppFlow(
    activity = this,
    apiKey = getString(R.string.itwing_sdk_app_id),
    mainActivity = MainActivity::class.java,
    // host splash/terms/onboarding options used by this sample
)
```

For hosts that do not use the startup flow, the SDK also exposes `initialize(...)` and `onReady(...)` in `ITWingSDK`.

## Preferred inline integration

Use `ITWingAdView` with the `ITWingAdViewPlacement` attribute:

```xml
<com.itwingtech.itwingsdk.ads.ITWingAdView
    android:id="@+id/ad_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:ITWingAdViewPlacement="@string/home_bottom" />
```

The host supplies only a semantic placement resource. The current Admin/configuration can resolve that placement to Native Small, Native Large, normal Banner, or a supported collapsible Banner without changing host XML.

For collapsible ads, anchor the placement at the actual application-content edge:

- Collapsible Top belongs at the top edge, with content below it.
- Collapsible Bottom belongs at the bottom edge, with content above it.

Do not place collapsible placements inside scrolling content or between ordinary content rows.

## Current public integrations demonstrated

- `ITWingSDK.startAppFlow(...)` for startup, onboarding, and terms.
- `ITWingAdView` for inline ads.
- `ITWingSDK.showInterstitial(...)`.
- `ITWingSDK.showRewarded(...)` and `showRewardedInterstitial(...)`.
- `ITWingSDK.showAppOpen(...)`.
- `ITWingSDK.ads.preloadAll(...)` for SDK-owned fullscreen ad preloading.
- `ITWingSDK.bindSubscriptionControls(...)`, `showPurchaseDialog(...)`, `launchSubscriptionPurchase(...)`, and `restorePurchases(...)`.
- `ITWingSDK.refreshConfig(...)`, `checkForUpdates(...)`, `syncNotificationToken(...)`, and `ITWingSDK.analytics.track(...)`.

## Legacy compatibility

`ITWingBannerView` and `ITWingNativeAdView` remain public for existing host applications. They are compatibility APIs; new integrations should use `ITWingAdView` and `ITWingAdViewPlacement`.

## Example screens

The sample keeps separate, screen-edge demonstrations for top and bottom collapsible ads, unified Native Small/Large and Banner placements, interstitial recovery, Premium, startup flow, billing, App Open, and other current public helpers. It does not call Google ad-loading APIs directly; those remain inside the SDK.
