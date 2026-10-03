# Changelog

## v1.52.1 — startup sequence hotfix

- Restore the established Mobile Ads initialization timing from after SDK configuration and purchase restoration; remove the v1.52 speculative parallel startup and extra readiness gate.
- Keep the v1.52 generic `background`, `surface`, `text`, and `secondary` admin palette mappings.
- Preserve approved ad visuals, AdMob-first/custom-fallback behavior, and existing host APIs.

## v1.52 — palette and startup reliability

- Map the app-level `background`, `surface`, `text`, and `secondary` palette tokens into Native and Banner ad surfaces/text when no more specific ad color is configured.
- Begin Google Mobile Ads initialization as soon as SDK config is available, deduplicate overlapping initialization requests, and keep inline requests gated until configured Google Play ad-removal purchases have been restored.
- Prevent the SDK splash flow from starting a late fullscreen ad when startup initialization exceeds its bounded wait.
- Preserve approved v1.49 ad visuals, AdMob-first/custom-fallback behavior, suppression behavior, and existing host APIs.

## v1.51 — corrected source release

- Rebuild from the approved v1.49 candidate source so the finalized Native Small/Large, Custom Native, Banner, card, color, and shimmer presentation is included in the published artifact.
- Retain v1.50 post-interstitial inline-ad suppression and AdMob-first/custom-fallback behavior.
- No intentional ad-layout redesign or public API change.

## v1.50

- Keep Google AdMob as first priority; start Custom fallback only after terminal Google failure/no-fill.
- Add process-scoped post-interstitial Native/Banner suppression with the admin-controlled `suppress_inline_ads_after_interstitial` setting; suppressed placements make no ad request and show no shimmer.
- Preserve banner fallback/lifecycle guards, foreground-only fullscreen protections, actual-impression callbacks, and the approved card/shimmer presentation.
- Maintain the v1.48-compatible public API and existing host integration.

## v1.49 — release candidate

- Normalize native and banner ad presentation from the existing admin `app.colors` contract; honor dedicated CTA, text, background, and stroke colors before generic theme fallbacks.
- Keep custom native and banner creatives aligned with admin-controlled styling, and use existing `media_shimmer_base_color` / `media_shimmer_highlight_color` keys for ad shimmer before safe generic fallbacks.
- Apply the same normalized admin color resolution to fullscreen custom-ad text, badge, CTA, and background roles.
- Make malformed color values fall back safely rather than failing during view styling.
- Keep the native loading shimmer visible during configured real-to-custom fallback; terminal failures still clear loading UI.
- Carry forward the Android v1.48-compatible realtime APIs and centralize the release version used in SDK request headers and telemetry.
- Harden banner registration, native-ad view inflation, and interstitial display against incompatible ad view/type casts so recoverable SDK errors do not crash the host UI.
- Treat null or destroyed Activity inputs at Android ad-entry points as recoverable: show callbacks complete deterministically, while preload calls safely no-op and inline placements hide.
- Normalize native/banner card background and radius through the shared presentation layer. New card defaults remain transparent and zero-radius; explicit v1.48 transparency opt-ins retain their legacy configured background.
- Gate Android Google ad requests on a single SDK-owned UMP consent flow, expose privacy-options hooks, and advance the Android Google Mobile Ads Next-Gen dependency to 1.4.0 with UMP 4.0.0.
- Guard App Open presentation with process foreground, resumed-Activity, foreground-session, first-run, cooldown, freshness, and shared fullscreen-ad checks; stale delayed requests are rejected before presentation.
- Add iOS source parity for legacy card-color compatibility and extend the existing macOS GitHub Actions workflow to run the iOS simulator test suite.
- Keep VPN server records visible regardless of reported health; health remains telemetry unless the host app applies an explicit filter.
- Retain existing public APIs and minimum Android version. Existing v1.48 integrations need no code changes.

## Validation boundary

This is a local candidate only. It has not been published to GitHub/JitPack or deployed to production. Android unit tests (17), SDK `lintDebug`, SDK release AAR assembly, unsigned sample release APK assembly, and ten connected instrumentation tests pass on the physical V2061 device. Instrumentation covers rendered real-native small/large styling, small/large custom-native colors, banner colors, shimmer colors, real/custom template dimensions, null/destroyed Activity entry points, template inflation, and shimmer startup. This does not substitute for real-ad-network/no-fill lifecycle testing, complete App Open lifecycle device scenarios, Google Native Validator QA, or the full backend preset screenshot matrix. iOS source/tests were statically reviewed only because Swift/Xcode are unavailable locally; the existing macOS workflow now includes simulator tests but has not been run from this local-only task. No iOS build/runtime certification is claimed.
