# Changelog

## ITWingSDK v1.63

- Fixed a production `ClassCastException` in `ITWingAdView` during attachment and asynchronous refresh.
- Hardened universal inline placement and renderer resolution against malformed runtime configuration and unsafe casts.
- Improved attach/detach/config-refresh safety without changing placement, rendering, or provider behavior.

## ITWingSDK v1.62

- Unified `ITWingAdView` integration for current placement-driven Native and
  Banner rendering.
- Stabilized screen-edge collapsible Top/Bottom banners and post-interstitial
  Native/Banner recovery.
- Hardened malformed-config handling, daily placement caps, Banner refresh
  intervals, lifecycle safety, and release/R8 behavior.
- Updated Premium, dialog, loading, Exit rating/feedback/review, and Admin
  theming consistency, including shared SDK CTA geometry.
- Modernized the example app and current public integration documentation.

## ITWingSDK v1.61

- Added explicit AdMob/custom provider attribution and per-request IDs to ad
  load telemetry so request, load, and impression reporting can be correlated
  without changing ad selection behavior.
- Preserved exact collapsible top/bottom request metadata and added bounded
  debug tracing for resolved type, request direction, and served collapsible
  result.
- Ensured Native post-interstitial recovery commits the approved shimmer for a
  render frame before starting the normal provider request; no arbitrary delay
  or fallback-preemption was added.

## ITWingSDK v1.60

- Added the public XML `ITWingAdView` that resolves a host-supplied placement
  name to the configured Native or Banner renderer without changing the host
  layout contract.
- Hardened malformed placement-format/config boundaries so invalid values fall
  back safely instead of crashing Terms or startup preload.
- Preserved collapsible top/bottom requests and post-interstitial first-touch
  inline recovery on the approved v1.57 runtime behavior.

## ITWingSDK v1.59.1

- Armed post-interstitial inline suppression when a fullscreen ad is actually
  shown, preventing destination Native/Banner loads from racing ahead of the
  first-touch release boundary.
- Preserved the admin suppression switch and the normal shimmer, AdMob-first,
  and Custom-fallback loading path.

## ITWingSDK v1.59

- Restored the approved v1.57 runtime baseline and layouts.
- Added safe collapsible-banner requests for supported top and bottom metadata.
- Added first-touch post-interstitial inline-ad recovery for current destination
  Native and Banner containers, preserving the normal shimmer, AdMob-first, and
  Custom-fallback loading path.

## ITWingSDK v1.58

- Hardened placement-format conversion against malformed or unsupported values.
- Improved interstitial preload and in-app-update safety.
- Added input-dispatch and lifecycle protection around inline-ad recovery.
- Preserved post-interstitial shimmer restoration and single-shot callbacks.
- Centralized collapsible-banner metadata mapping for `top` and `bottom`.
- Recorded whether a loaded banner was actually collapsible while retaining
  normal-banner fallback behavior.
- Preserved existing ad eligibility, frequency, and provider-selection rules.
