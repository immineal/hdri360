# 360 HDRI Camera

An Android app for capturing **genuine** HDRIs: street-view-style full-sphere
capture, physically linear radiance, auto-planned multi-exposure brackets, and
on-device stitching to an equirectangular HDR panorama.

Not a pretty-picture exposure fusion. The output is a linear radiance map you can
light a scene with — and where the device allows it, one with an absolute scale
in cd/m².

Licensed under the GNU General Public License v3.0 — see `LICENSE`.

## Status

| Part | State |
|---|---|
| `core/` radiance + stitching | **Complete.** Zero dependencies, runs on a bare JVM |
| `core-tests/` the suite | **Complete.** 29 suites, 512,461 assertions, all passing |
| `tools/` desktop re-stitch harness | **Complete.** Stitches a folder of ordinary photographs |
| `app/` Android capture + UI | **Builds and runs.** A whole sphere, on the phone |
| Release, Play Store, F-Droid | Not yet |

Measured on a Pixel 9a, on a real capture: `LINEAR_RAW` tier, a five-rung ladder
from 1/1110 s ISO 29 to 1/15 s ISO 906, **0.110° bundle residual**, **13.5 stops**,
peak **12,752 cd/m²** on an absolute scale.

This is a Kotlin rewrite of a verified Java implementation, held to exact
behavioural parity rather than merely to passing tests. The Kotlin core
reproduces the Java original's assertion count exactly, every measured
diagnostic to every printed digit, and — on 30 real handheld photographs —
**byte-identical output files**, with recovered pose matrices differing by 0.0.

Three things are deliberately un-idiomatic because behaviour depends on them:
`java.util.Random` rather than `kotlin.random.Random` (different algorithm, and
`nextGaussian` consumes a variable number of draws); `Locale.US` pinned in every
`toString`; and the test harness still catching `RuntimeException` only.

## Building

```bash
# The whole core suite, on a bare JVM. No Android SDK required.
./verify.sh          # pass/fail
./verify.sh -v       # plus every measured diagnostic

# The app.
./gradlew :app:assembleDebug
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

## What it refuses to claim

Three capability tiers, and the output says which one produced it:

| Tier | What it means |
|---|---|
| `LINEAR_RAW` | RAW plus a manual sensor. A measurement: absolute cd/m² is possible |
| `MANUAL_YUV` | The bracket is ours, the pixels came through the camera's tone curve. Reconstructed |
| `LOCKED_AUTO` | The camera chose. Locked so every frame shares one setting, and relative only |

Radial distortion is estimated in the bundle adjustment — and refused when the
data could not have measured it. A residual improvement is not evidence: what
makes `k1` observable is seeing the same point at *different* image radii in two
frames, so a capture whose frames all share one aim carries no information about
the lens at all. The recovered coefficient is asked what image displacement it
claims to have measured, and rejected if the answer is below the noise.

Every direction that could not be shot is reported as a hole, never counted as
captured.

## On the phone

- **Guided sphere.** Scan sweep first for one global ladder, then targets to
  point at. The plan takes its roll from `SENSOR_ORIENTATION`, so "upright" means
  upright to the person holding the phone rather than to the sensor inside it.
- **Aim and roll are judged separately** — and at the poles roll is not judged at
  all, because there it is a heading, and the zenith is shot with the screen
  facing the floor. What happens up there is felt: a tick on aim, a firmer one
  when the frames are stored, three when the sphere is finished.
- **Every frame is written down as it is taken**, journaled and fsynced, so a
  capture killed at frame 140 resumes on the ladder it started with.
- **Processing is on device**, in a foreground service, with an estimate measured
  on that phone rather than borrowed from another one.
- **Merged directions are parked on disk**, one opened at a time by the
  compositor. Peak memory is one frame however big the sphere is — which is what
  lets the working resolution follow the sensor instead of the heap.
- Output is a half-float linear EXR, a JPEG preview and a `report.json` saying
  what the numbers mean.

## Privacy

There is **no network permission in the manifest**. Nothing is collected and
nothing is sent. The app keeps a local log of what it did; a diagnostics bundle —
the log, the device, the camera's stream plans, and each capture's own
bookkeeping — can be written to Downloads and shared deliberately by the person
holding the phone. No frames are ever included.
