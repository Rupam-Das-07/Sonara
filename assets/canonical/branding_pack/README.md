# Sonara Android Branding Asset Pack

Source-of-truth: supplied Sonara Web branding assets, with the black symbol-only S used as the canonical geometry and recolored for light/dark contexts.

## Android adaptations
- Adaptive icon foreground: transparent Bone S, optically centered and kept within the Android keyline.
- Adaptive icon background: Sonara Mineral Petrol.
- Monochrome: single-color S silhouette, no gradients or baked background.
- Legacy launcher: precomposed Petrol + Bone S.
- Splash: transparent S artwork only; dark = Bone on Petrol, light = Petrol on Bone.
- Wordmark is intentionally excluded from launcher/splash icon artwork.

## Color tokens
These are centralized in `android-res/values/colors.xml`:
- Mineral Petrol: #20383A
- Bone Canvas: #E8E1D4
- Oxide: #9A5B46

## Integration
Copy the contents of `android-res/` into the Android module's `src/main/res/`.
The SVG and PNG sources are in the `master/`, `launcher/`, and `splash/` folders.

## Important
The supplied reference set did not contain explicit hexadecimal palette values; the three semantic color values above are therefore centralized editable tokens rather than claimed historical source values. The logo geometry itself is derived from the supplied canonical symbol-only asset.
