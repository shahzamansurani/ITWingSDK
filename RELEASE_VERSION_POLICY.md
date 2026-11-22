# ITWingSDK release version policy

ITWingSDK Android releases use exactly one numeric minor component after `1`:

- `v1.65`
- `v1.66`
- `v1.67`
- `v1.68`
- `v1.69`
- `v1.70`

Patch-style tags such as `v1.65.1`, `v1.66.1`, `v1.67.1`, `v1.68.1`, `v1.69.1`, and `v1.70.1` are not valid
release versions. The `itwingSDK` Gradle build validates this rule during
configuration, so a release build fails before publication when the supplied
`version` property does not match `v1.<minor>` (or `1.<minor>`).

Historical tags are immutable evidence and must not be moved or deleted. The
next release is always the next unused numeric minor version.
