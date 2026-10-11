# Changelog

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
