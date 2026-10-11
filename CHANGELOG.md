# Changelog

## ITWingSDK v1.58

- Hardened placement-format conversion against malformed or unsupported values.
- Improved interstitial preload and in-app-update safety.
- Added input-dispatch and lifecycle protection around inline-ad recovery.
- Preserved post-interstitial shimmer restoration and single-shot callbacks.
- Centralized collapsible-banner metadata mapping for `top` and `bottom`.
- Recorded whether a loaded banner was actually collapsible while retaining
  normal-banner fallback behavior.
- Preserved existing ad eligibility, frequency, and provider-selection rules.
