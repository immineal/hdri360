# 360 HDRI Camera

An Android app for capturing **genuine** HDRIs: street-view-style full-sphere
capture, physically linear radiance, auto-planned multi-exposure brackets, and
on-device stitching to an equirectangular HDR panorama.

Not a pretty-picture exposure fusion. The output is a linear radiance map you can
light a scene with.

Licensed under the GNU General Public License v3.0 — see `LICENSE`.

## Status

This is a Kotlin rewrite of a verified Java implementation. The port is
mechanical and held to exact behavioural parity: the full suite runs against
both trees and the assertion counts and every measured diagnostic must match.

| Part | State |
|---|---|
| `core/` radiance + stitching | port in progress |
| `core-tests/` the suite | port in progress, green at every step |
| `tools/` desktop re-stitch harness | not yet ported |
| `app/` Android capture + UI | not yet written |

## Building

```bash
# The whole core suite, on a bare JVM. No Android SDK required.
./gradlew :core-tests:verify -PsuiteArgs="-v"

# While the port is in progress, unported suites report MISS rather than failing:
./gradlew :core-tests:verify -PsuiteArgs="--allow-missing -v"
```

## Why linear

**Linear from end to end.** RAW when the device offers it: black level off,
white level normalised, merged in the Bayer domain before demosaicing, so clipped
and unclipped samples are never smeared into each other. Where RAW is not
available the camera's response curve is recovered from the bracket itself
(Debevec-Malik) and divided out before anything is called radiance.

**The merge is an estimator, not a blend.** Inverse-variance weighted mean of
`v/e` under an affine sensor noise model, weighted by the *predicted* value at
each exposure rather than the measured one, because weighting by a noisy
measurement correlates the weight with its own error and biases the mean low.

**One exposure ladder for the whole sphere**, so every frame lands on the same
radiance scale and stitching never reconciles two calibrations.

Nothing in the radiance path may gamma-encode, tone map, sharpen or
auto-white-balance. The only tone mapping in the codebase exists for the
on-screen preview.
