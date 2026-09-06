# Decisions taken, and why

Settled with the project owner on 2026-09-05, after a full sphere came off the
phone covered end to end and stitched badly. Written down because a chat log is
not a place decisions survive.

Order of work, chosen rather than dictated: the growing ladder first, because it
is the root of the bad stitch and because it needs the phone to verify. Then the
colour space and the report marking, both of which can be checked offline
against the capture already pulled. Then the lenses, then the first-run
explanation, then the tracker.

## Capture

**1. The exposure ladder grows during the capture.** When a direction comes back
clipped, a shorter rung is added and that direction is re-shot immediately, while
the person is still pointing at it. Directions already finished are left alone:
a direction that did not clip does not need the new rung.

This replaces the rule that the sweep had to be complete before capture could
start. It moves responsibility for a complete measurement off the person and
into the app, which is where it belongs. The alternative considered and rejected
was to shoot one very short insurance rung at every direction: simpler, but it
spends a frame per direction that is usually wasted and still guesses at how
short is short enough.

**2. Nothing may clip**, at the cost of an extra frame per direction where
needed.

**3. Where that is physically impossible** - direct sun in a window is brighter
than the shortest exposure the sensor has - the capture proceeds and the report
says the top value is a lower bound rather than a measurement.

**4. No live warning when a direction clips.** Planning is supposed to prevent
it. An indicator would be an admission that it does not.

## Lenses

**5. Every camera is enumerated**, including the physical cameras behind a
logical one. On a Pixel 9a the ultrawide is not in `cameraIdList`; it sits behind
camera 0, which reports `LOGICAL_MULTI_CAMERA` and lists its physical ids.

**6. A lens without RAW is still offered**, with its tier stated: `MANUAL_YUV`
rather than `LINEAR_RAW`, and no absolute cd/m². The person chooses with the
trade-off in front of them rather than being protected from it.

**7. The default is the widest lens that can shoot RAW.** On the 9a the ultrawide
would cut the sphere from 34 directions to roughly 15, which halves the time
spent standing in the room and raises the overlap between neighbours.

## Output

**8. The EXR is written in linear Rec.709**, not in the camera's own RGB. Today
the pipeline applies white balance gains and stops, so the file is in an
undefined space that reads green and undersaturated. The camera supplies the
matrix (`COLOR_CORRECTION_TRANSFORM`) and it is already read for the preview; it
is simply not carried through.

**9. Directions placed on the orientation prior alone are marked in the report.**
The capture is not interrupted for them. A hole that is named is a fact the
person can act on; a hole that is silent is a soft seam nobody can explain.

## First run

**10. A short animated sketch before the first capture**, skippable.

**11. It covers the whole flow briefly**: sweep, work through the directions, let
it process, look at the result. Four sentences, not a manual.

**12. A question mark in the corner, reachable at any time**, including during
capture.

## Library

**14. Spheres are named and browsable in the app.** A capture directory is
called `capture-1788604964095`, which is right for a thing that must survive the
process being killed and wrong for a thing somebody wants to open next week -
and the app could only ever offer the single most recent one, so a sphere shot
yesterday was on the phone and unreachable.

Decided while looking at a phone holding eleven captures, seven of them with
spheres in them, all indistinguishable.

Three choices inside it that could have gone the other way:

  - **A capture nobody named has no name.** The alternative - filling it in with
    the date at capture time - produces a library where every row looks named
    and you have to read each one to find the one you actually named. An unnamed
    sphere is titled by when it was shot, formatted in the device's own locale,
    and the name stays genuinely empty.
  - **The name is a file in the capture directory**, not a database. It travels
    with the bundle, needs no migration, and a capture pulled onto a desktop
    keeps what it was called. The export is named after it too, because a folder
    of `capture-1788604964095.exr` is a folder nobody can use.
  - **Every row says what is wrong with its sphere**, if anything: highlights
    that are a bound rather than a measurement, directions placed on the phone's
    orientation alone, radiance not in Rec.709, directions that were not placed.
    Those are what decide whether a sphere is worth keeping, and a library that
    made you open each one to find out is a library where nobody finds out.
    A capture written before the colour matrix existed says "colour space not
    recorded" rather than "camera RGB" - it happens to be camera RGB, but
    nothing on disk says so, and the difference between knowing and assuming is
    the whole point of the report.

The naming rules live in `core`, in `SphereLibrary`, and are tested on a bare
JVM like everything else; the Android side is a list and a text field over them.

### The file was in no unit at all

Reported on 2026-09-05: as a Blender world texture the sphere is about a hundred
times too bright, and strength 0.01 looks right.

The radiance scale was computed, put in the report, and **never applied to a
pixel**. So the file was in the pipeline's own unit - a sensor fraction over a
relative exposure - and the factor from that to cd/m2 is `78 N^2 / (q * baseIso)`,
which depends on the lens:

| | cd/m2 per file unit | file mean | actual |
|---|---|---|---|
| main lens | 11.96 | 197.5 | 2362 cd/m2 |
| ultrawide | 11.62 | 143.5 | 1667 cd/m2 |

The physics was right - 1700 to 2400 cd/m2 mean and 45 to 142 thousand at the
peak is a sunny garden - but two spheres of the same room, one from each back
lens, came out at different brightnesses while both reported an absolute scale.
A file whose brightness depends on which lens took it is not on an absolute scale
in any useful sense.

**16. The EXR is written in kilocandela per square metre**: 1.0 means 1000
cd/m2, when the capture earned an absolute scale. Lens-independent, one
documented constant, and it puts ordinary outdoor scenes between 1 and 50 -
where every environment map in the wild lives - so a renderer at strength 1.0 is
right with nothing to adjust. The measured factor between the old unit and the
new one is 86, which is the "about a hundred" that was reported.

**17. A capture with no absolute scale is normalised to a median of 1.0**, and
the report says it was normalised rather than measured. There is no cd/m2 to be
faithful to on a camera that drove its own exposure, so what is left is to make
the file openable and to be plain about what the numbers are not.

The report now carries `fileUnit`, `cdPerM2PerUnit` and `fileScaleBasis`, and
`maxLuminanceCdPerM2` is derived from the file's own pixels times the file's own
stated unit - so it can be checked rather than believed.

## Later

**21. A finished sphere can be looked at by turning the phone.** Asked for on
2026-09-05: hold the phone up as a window and look around the room the capture
was taken in, with zoom and drag still working.

It comes almost free, and the reason is worth writing down: the panorama's
heading and the phone's heading are the *same* frame - both are magnetic north,
one recorded at capture time and one read now - so feeding the screen's pose
straight into the viewer anchors the sphere to the room. Stand where it was shot,
turn, and the window is where the window is.

Three decisions inside it:

  - **The screen's pose, not the sensor's.** `OrientationMath.screenToWorld`
    rather than `cameraToWorld`: the sensor is mounted a quarter turn from the
    way a phone is held, and a viewer built on the camera pose works in portrait
    and lies in landscape. The two frames differ by a half turn about the
    device's own up axis, determinant +1 - and this app has already shipped one
    mirrored viewer, so the derivation is in the code and the orientation suite
    asserts it.
  - **The drag becomes a heading correction**, applied on the world side so it
    turns the room around the viewer rather than turning the viewer inside an
    already-anchored room. Indoors the compass is frequently tens of degrees out
    and there has to be a way to fix that by hand.
  - **Pitch comes from the phone alone** while it is following. Two sources of
    pitch at once is a view that fights the hand holding it.

Off by default, and the button is absent rather than dead on a phone with no
rotation sensor: somebody who opens a sphere sitting on a sofa should not have it
swing away from them.

Not yet looked at on a screen.

**15. The capture animation shows the progress, not the instructions.** What is
on screen during a capture should say which directions are done and which are
left, from the physical state of the thing - and say it simply, without text and
without ornament. It also has to carry the one thing that currently surprises
people: when a direction comes back burnt out the ladder grows and the capture
gets *longer*, and a progress indicator that does not account for that reads as
a stall.

Asked for on 2026-09-05, after the first 21-direction capture.

What went in, and why each piece is information rather than decoration:

  - **Progress counts directions, not frames.** The frame count grows when a
    direction burns out, so a fraction with frames underneath *falls* at the
    moment the app decides to do more work - indistinguishable from a stall, at
    exactly the point where the capture starts taking longer.
  - **A re-shot direction carries a ring per extra rung it needed.** The capture
    visibly took longer there and nothing on screen said why; a mark stays put
    for the rest of the sweep, where a line of text is read once and ignored.
  - **A bracket in flight fills a ring as its frames land.** The only thing on
    the screen that says "keep holding". A burst is a fifth of a second on an
    easy direction and two and a half on one being shot again, and all of it was
    spent holding a phone still with nothing to go on. Drawn from frames rather
    than from a timer, so it tracks the storage actually finishing.
  - **Two blocks of standing text are gone.** The tier note never changes during
    a capture and the lens picker already says it. The instruction now stops once
    it has been acted on, rather than covering the part of the sphere the person
    is being asked to look at for the whole phase.

## Open, in the order they are being worked

Written down because a backlog held in a conversation is a backlog that gets
lost. Each of these was either raised by the owner or found by measurement during
2026-09-05; nothing here is speculative.

**1. `k1` is sensitive to initialisation, and once shipped at its clamp.**
Mostly done - see below. What remains is whether the coefficient should be solved
at all when the device will simply report it, and that needs one reading off the
phone which the diagnostics now dump.

**2. The offline probe could not reproduce the phone - fix was incomplete, and the
second half is only now in.** The map was being *asked for* on the wrong request,
so no capture ever recorded one; see "The shading map was never recorded" below.
Still awaiting a capture that actually carries it.
On the same capture the probe solves 55 pairs in four pieces where the phone got
62 in one, and the two spheres end up about forty degrees apart in heading.
Heading is arbitrary for lighting where tilt is not, so neither sphere is broken -
but the probe is the measuring instrument for everything else in this file, and
an instrument that does not reproduce what it measures is worth understanding.

Diagnosed: the phone applies the camera's **lens shading map** when it converts a
RAW frame, so the frames it stitches are vignetting-corrected. The DNG bundle is
raw and the map is nowhere in the capture - so the probe stitches frames whose
corners are up to a stop darker, which changes feature contrast exactly at the
frame edges where the overlap is. The averaged black level is not a candidate:
the four values measured 64.02, 64.01, 64.01, 64.01 out of 1023.

**Done**: the map is now recorded in `session.json` alongside the black and white
levels, which also makes a bundle faithfully re-processable years later - the
point of keeping one at all. The interpolation moved into the core as
`ShadingMap` so that the phone and the probe run the same code over the same
numbers rather than two implementations that agree by inspection.

Only verifiable on a capture taken after the change: `sphere-34b` has no map, and
the probe now says so out loud rather than quietly working on dark corners.

**3. Decisions 10 to 12, the first-run sketch. Built, not yet looked at.** Four
steps, one sentence each, every one of them drawn *moving* rather than described -
the ring filling as the phone turns, the reticle finding a direction and holding,
the processing arc, and the exposure slider sweeping a window from white to
something with bars in it. Skippable from the first frame; reachable afterwards
from a question mark in the corner. The phone was unplugged before it could be
seen on a screen, so the drawings are unverified.

**4. Waiting on a main-lens capture** with the current build, and one reading
from the phone: the diagnostics now dump `LENS_DISTORTION` and
`LENS_INTRINSIC_CALIBRATION`. If the device reports its own distortion there is
nothing for the bundle adjustment to solve, and the sensitivity above stops
mattering. The phone was unplugged before that could be read.

The capture itself is to see three things that could not be seen on the ultrawide
one:

  - the 30 degree roll tolerance. Only the main lens's plan has rings at plus and
    minus 55 degrees, which is where the roll error was measured at up to 9.3
    degrees; the ultrawide's rings are at 0 and plus or minus 38, where it was
    1.7 to 5.4 and would have passed the old 15 degree budget too.
  - the levelling, against the sun. Verified arithmetically on the old capture's
    own poses - elevation 35.3 to 44.5, into the almanac's band - but not yet on
    a sphere that came out of the phone already level.
  - the new overlay marks in use: the ring per extra rung, and the burst ring.

**5. The handheld shutter limit was too generous. Set to 1/30.** What remains is
the other half of the trade: the sharpness side was measured, the shadow-noise
side is still shot-noise theory. One attempt to measure it failed on scene
texture - see above for what would work instead, which is flat sky or a tripod
pair rather than a garden.

**6. A trimmed bracket left orphan frame files. Done.** When a direction grows
past `maxPerTarget` the run is trimmed at the bright end, so the re-shot burst
writes fewer positions than the previous attempt and the file at the old last
position stayed on disk. Harmless - nothing read it, because the plan says how
many rungs a direction has - but it inflated the capture's size on disk and the
library's row for it. `FrameStore.planChanged` now drops what a revised plan has
no place for.

## Not now

**13. Release, Play Store and F-Droid wait** until the spheres are right.

## Where these stand

Written as they are done, so the next session does not have to read the code to
find out.

| # | Decision | State |
|---|---|---|
| 1 | The ladder grows during the capture | **Done.** `BracketPlanner.extendDarker`, driven from `CaptureController.extendForClippingLocked`. Verified against a simulated room with a window; **not yet on the phone** |
| 2 | Nothing may clip | **Done**, as the same change. One extra frame in the direction that needed it |
| 3 | Where it is impossible, the report says so | **Done.** `highlightsAreLowerBound` in the report, from `BracketPlan.withDarkEndClamped` |
| 4 | No live warning when a direction clips | **Held.** The status line says a direction is being shot again; there is no clipping indicator |
| 5 | Every camera enumerated | **Done and checked on the phone.** `CameraProbe.lenses` walks `physicalCameraIds`; the streams are bound with `OutputConfiguration.setPhysicalCameraId` |
| 6 | A lens without RAW is still offered | **Done.** `Lens.tier` is carried, `LensChooser.describe` states it, nothing is filtered out |
| 7 | Default is the widest lens that can shoot RAW | **Revised on the phone.** Widest *that the camera will deliver from*: a lens that opens as itself beats a wider one that exists only as a physical stream. The 9a's ultrawide gave two frames out of eighty-four; see below. Still offered, no longer the default |
| 8 | The EXR is in linear Rec.709 | **Done and checked on a real capture.** `StoredSession.colorMatrix` carries the camera's `COLOR_CORRECTION_TRANSFORM`; `StoredCapture.colorTransformFor` composes it with the white balance into one 3x3, which `HdriPipeline` applies to the **merged** radiance |
| 9 | Prior-only directions marked in the report | **Done.** `HdriPipeline.Result.placedOnPriorAlone`, reported per pose and counted as `framesOnPriorAlone` |
| 10-12 | First run sketch | **Built**, unverified on a screen. `IntroSketch`, four animated steps, skippable, plus a "?" that reopens it |
| 13 | Release | In progress. Warnings cleared, `allowBackup` off, R8 and resource shrinking on (12 MB to 1.4 MB), signing read from an untracked `keystore.properties` and falling back to unsigned, adaptive launcher icon drawn as vectors, the three capture faults below fixed |
| 24 | The order directions are offered in | **Left alone, asked and answered.** Nearest-first stays; the measurement behind the question is below |
| 25 | Notification permission is asked for | **Done.** Declared since the first release and never requested, so on Android 13 and up the processing notification never appeared. Asked at handover, and denial does not stop the work |
| 26 | Processing holds the CPU awake | **Done.** A foreground service is not a running CPU; a two hour bounded `PARTIAL_WAKE_LOCK`, released in the job's `finally` |
| 27 | An exposure never prints as `1/0s` | **Done.** Anything from half a second up prints in seconds; two and sixteen used to be the same string in the log |
| 28 | Capture failures reach the app's own log | **Done.** `onCaptureFailed` and `onCaptureBufferLost` went to logcat only, so the one file there is to go on said nothing about frames that never came. Short bursts now log what they returned |
| 29 | Resume is judged on the camera, not the count | **Done.** The guard compared direction counts; it now compares the recorded camera id and says in the log why it refused. Captures with no complete direction are no longer offered at all |
| 30 | Auto-rotate held while the phone is the window | **Done.** `screenToWorld` ignores display rotation by design, so a launcher swinging the UI ninety degrees mid-look would put the drawing a quarter turn out |
| 31 | A capture nobody can use is not kept forever | **Done.** `SphereLibrary.deleteAbandoned`, swept once per session off the main thread: unfinished, not one whole direction in it, more than a day old. Nothing used to remove these and no screen ever showed them |
| 32 | A physical lens says what it is | **Done.** `LensChooser.describe` marks a stream bound to a physical sub-camera "untested on this phone" rather than offering it as an equal |
| 34 | A sphere says whether its frames are still there | **Done.** `Entry.rawBytes`/`hasRawFrames`, shown per row and totalled in the header. 3.1 GB of DNGs against tens of megabytes of sphere, and nothing on any screen used to say so |
| 35 | The frames can go without the sphere | **Done.** `SphereLibrary.deleteRawFrames`, per sphere or for all of them at once, and it **refuses** on a capture that has no sphere yet - there the frames are the only copy. The review screen's own loop, which deleted gigabytes on one tap with no confirmation, now goes through it |
| 36 | An unfinished capture can be discarded | **Done.** `CaptureSession.discard`, offered beside "Resume it". The only ways past one used to be to finish it or to leave it there for ever |
| 37 | Licence and method are in the app | **Done.** `AboutActivity`: GPL v3, Apache 2.0 for Compose, and the published work each number comes from - Debevec and Malik for the merge, ISO 12232 for the scale, Lowe's ratio test, RANSAC and Levenberg-Marquardt, Kabsch/Procrustes, Brown-Conrady, OpenEXR. Every one is cited in the code that implements it |
| 38 | Privacy policy, and it is in the app | **Done.** `docs/privacy.md` and a Privacy section in `AboutActivity`, both resting on one checkable fact: no `android.permission.INTERNET` in the manifest, so nothing can be sent anywhere. **Play Console will want that file hosted at a URL** - that is the one release item that cannot be done from here |
| 39 | The screen reader has something to read | **Done.** The "?" and "..." buttons and the library thumbnails carry descriptions; they used to announce as "question mark" and "image" |
| 40 | A full disk says it is a full disk | **Done.** A whole burst delivered with not one frame written stops the capture and names storage. It used to work through all 32 directions saying "direction 5 kept failing; moving on" and finish by announcing nothing had been captured - the third fault of exactly this shape, after the refused burst and the lens that delivered nothing |
| 41 | Nothing is submitted to a shut-down executor | **Done.** A burst in flight keeps delivering after `close()`, and `settle` threw `RejectedExecutionException` straight into a Camera2 callback - `CRASH on hdri-camera` in the app's own log. All four hand-offs go through a `post` that checks `closed` under the lock |
| 42 | The shading correction is precomputed, not per pixel | **Done and measured on the phone.** 7774 ms a frame to 215 ms; a whole 34 direction sphere captured with nothing given up. `ShadingMap.samplerFor`, asserted bit for bit against `gainAt` |
| 43 | The frame duration is one the sensor is offered at | **Done.** `StreamLadder.frameDurationFor`; the 9a's floor for RAW at 4000x3000 is 33.3 ms and the shortest rung was asking for 9.3 |
| 44 | The bench hatch frees roll as well as aim | **Done.** It could not fire a single burst on a desk before |
| 45 | One bar, one journey | **Done.** The pipeline reports a fraction that runs 0 to 1 *within each stage*, and the service passed each straight through - so the bar filled and reset **four times** over eight minutes with the estimate resetting alongside it. The capture screen did the same across two phases. `StageProgress` in core, monotonic by construction, with every weight measured |
| 46 | The bench hatch was left armed on the phone | **Fixed by removing the file.** `/data/local/tmp/hdri360-anyaim` was still there after the diagnosis session, which is why the same direction could be shot over and over: with the aim check off, every direction is "aligned" from wherever the phone points. Not a bug in the app, a diagnostic left behind |
| 47 | The shading gain goes on merged radiance | **Done.** It was inventing blown highlights out of dim corners - a window was re-shot for a highlight that was never lost - and clamping away four fifths of the corner highlight range. `HdriPipeline.Options.shading`, beside the colour matrix |
| 48 | The conversion is off the camera thread | **Done.** The camera thread now does one bulk copy out of the buffer; the arithmetic is `RawPlane.convert` in the core, under test, on the worker |
| 49 | The estimate learns from what happened | **Done.** `WorkCorrection`. The startup benchmark predicted 64 s for a job that took 20, so the size picker quoted three times too long *before* the choice and the bar divided by a wrong number |
| 50 | The finger can shift the horizon while the phone steers | **Done.** "Turn to look" allowed only yaw; pitch was skipped on the theory that a dragged pitch would fight the hand. It does not - it shifts the horizon, which is what somebody standing in the wrong spot wants, and "Reset view" undoes it. Yaw on the world side, pitch on the camera side, so it stays "up relative to the phone" as the phone turns |
| 51 | Back out of the viewer does not close the app | **Done.** It landed on the finished-processing screen - a full progress bar for work already done - and going back again quit. Opening the viewer acknowledges the result, and Back on a finished job returns to the start screen |
| 52 | The start screen, rebuilt | **Done.** A tagline, two sentences per lens, three lines per card and a paragraph about the diagnostics button are gone. Lenses read as facts (field of view, directions, tier); the primary action is unmistakable; the lower half is filled with the person's three most recent spheres, thumbnails and all, loaded off the main thread |
| 53 | The lens picker highlighted the wrong lens | **Done.** `remember { state.chosenLens }` captured the value at the first composition, which happens before the camera list has been read - so the screen marked whichever lens sorted first and never corrected itself. Caught on a screenshot while the log said `-> default 0` |
| 33 | Impatient before the first frame | **Done.** `firstFrameTimeoutNs` is 4 s until the camera has ever delivered anything, then the full 12 s. A dead lens is diagnosed in about twelve seconds of standing still instead of thirty-six; a whole bracket measures 0.1 s, so the margin is fortyfold |
| 22 | A camera that delivers nothing says so | **Done.** `CaptureController` fails the capture outright rather than abandoning twenty-one directions in turn |
| 23 | A busy camera costs the direction nothing | **Done.** A refused burst is a timing fact, not a failed attempt, and waits the ordinary interval |
| 20 | Report format is version two | **Done.** A key changed meaning under an unchanged version; the field list is now pinned by a test |
| 19 | Report statistics use Rec.709 luma | **Done.** The cd/m2 figure was derived from a channel mean |
| 18 | Handheld limit 1/30 s | **Done.** `CameraProbe.HANDHELD_LIMIT_SECONDS`, with the measurement in the comment |
| 21 | Turn the phone to look around a sphere | **Built**, unverified on a screen. `OrientationMath.screenToWorld`, `ScreenPose`, and a switch in the review screen |
| 15 | Capture animation shows progress | **Done**, pending a look on a real capture. Progress counts directions so it cannot run backwards; a re-shot direction carries a ring per extra rung; a bracket in flight fills a ring as its frames land; two blocks of standing text removed |
| 16 | EXR in kilocandela per m2 | **Done.** `FileScale`, applied in `OutputWriter` and to the viewer's copy |
| 17 | No absolute scale, normalise to median 1 | **Done**, as the same change, and the report says which |
| 14 | Spheres named and browsable | **Done.** `SphereLibrary` in core, `LibraryActivity` over it; naming also offered on the review screen, and the export takes the name |

### The evening every capture died, and the three faults behind it

Two capture attempts on 2026-09-05, both in a dim room, both ending with nothing.
The log said `the camera refused the burst` and twenty-one directions abandoned
one after another, while somebody stood there holding the phone as still as they
could. Three separate faults, all mine, all from that day's work.

**The ladder planned frames no hand can take.** From the log:

```
ladder: 1/30s ISO787 | 1/29s ISO4824 | 1/5s ISO4824 | 1/1s ISO4824
```

`DeviceExposureLimits.realize` spends shutter up to the handheld limit, then ISO,
and once ISO is exhausted goes *back* to a longer shutter - up to whatever the
sensor allows, which here is sixteen seconds. Right for a tripod. On a hand it
produced a four rung burst ending in a full second, which outlasted the
controller's own twelve second patience. Halving the handheld limit to 1/30 s did
not create this; it uncovered it, because the fallback now starts a stop sooner.

The bright end of the ladder now stops where the hand does - `maxHandheldRelative-
Exposure()` - and says so through `clampedHigh`, which is what that flag has always
meant. A room darker than a handheld capture can reach is a fact to report, not a
reason to plan a one second frame. The shadows come back noise-limited instead.

**A refusal was charged to the direction.** `captureBracket` returning false means
the camera is busy, almost always still finishing the previous burst. That was
counted as a failed attempt against the direction being aimed at - and refusals
arrive at whatever rate the orientation sensor ticks, so three of them landed
inside forty milliseconds and the direction was given up for good. Then the next,
and the next. From the log: `direction 1 of 21 settled (0 shot, 1 given up)`
**thirty-one milliseconds** after the refusal.

A refusal now costs the direction nothing and waits the ordinary bracket interval
before trying again.

**And the reason the bursts were slow in the first place: the wrong lens.**
This is the one that mattered. Buried in the same log:

```
camera 0:3: 4208x3120, working at 1/2
```

Logical camera 0, physical sub-camera 3 - the ultrawide, reached through
`OutputConfiguration.setPhysicalCameraId`. The working 34-direction bundle from
earlier the same day records `"camera":"0"`: the main lens, opened as itself,
137 frames in one capture. On the physical binding, **two frames out of
eighty-four arrived in seventeen seconds**. Preview, metering and the entire scan
run fine on it; it is RAW_SENSOR that does not arrive.

So decision 7 keeps its argument and gains a second clause. Width still beats
focal length - 21 directions against 34, six partners per frame against four - but
a whole camera beats a physical stream, because a field of view nobody can capture
is worth nothing. The ultrawide is still enumerated, described and choosable; it
is not what the app starts on. A preference, not a prohibition: on a phone that
lists its ultrawide in `getCameraIdList()` it wins outright.

**What the app did while all this happened is its own fault.** Three bursts handed
over, well past a minute of standing still, not one frame back - and the app
worked patiently through the remaining twenty directions abandoning each in turn.
Nothing the person could have done differently would have changed that. A lens
from which not a single frame has *ever* arrived is broken for this purpose, and
the capture now stops at once and says so, naming the lens rather than a
direction. A camera that has delivered before keeps the old per-direction
handling, because there the sphere may still be worth finishing.

### The window that was never burnt out

His observation, looking at a finished sphere: the app had announced that a
direction with a window in it "came back burnt out" and re-shot it - and yet at
minus three and a half stops there is detail in the sunlit white wall behind that
window. The highlight it claimed to have lost had never been lost.

The arithmetic says why, and it is a fault from the same day the shading map was
first recorded.

The correction was being applied to the **sensor fraction** and the result
clamped into [0, 1]. This phone's map peaks at a gain of 5.03. So a corner sample
above `0.98 / 5.03 = 0.195` came out at or past `saturationThreshold`, and
`highlightsClipped` declares a direction blown at `clipTolerance = 1e-4` - a
tenth of a percent of the frame, three thousand pixels of three million. A bright
corner at a fifth of white was enough to condemn the whole direction.

Two faults out of one mistake:

- **saturation invented where there is none**, which costs a re-shoot, spends the
  person's time, and tells the report that the top of the range is a lower bound
  when it is a measurement;
- **real corner signal destroyed**, because everything above `1/gain` clamped to
  the same 1.0 - on this map that is four fifths of the highlight range in the
  corners.

Saturation is a property of the sensor well. The shading gain is a multiplicative
correction, it commutes with the exposure scaling, and radiance has no ceiling to
clamp against. So it goes exactly where the colour matrix goes and for exactly
the same reason: on the **merged** radiance, once, after the merge has weighted
each rung by how saturated the sensor actually was. `HdriPipeline.Options.shading`,
next to `colorTransform`, in that order - sensor saturation first, then the flat
field, then the colour matrix.

Which also means the gain has to be de-mosaiced, because merged radiance is RGB
and the map's four planes are R, Gr, Gb, B: a demosaiced green came from both
green sites and is corrected by their mean. `ShadingMap.RgbSampler`.

This is the third time this shape of mistake has cost something in this project -
after the colour matrix applied per rung (a magenta sphere) and the shading
applied per pixel on the camera thread (eight seconds a frame). The rule it keeps
teaching: **a correction that is not a property of one rung does not belong on one
rung.**

Side benefit: the capture path no longer touches the shading at all, so the 200 ms
per frame it cost is gone from the camera thread as well.

**Confirmed on the phone**, on the first capture after the change: the map is
recorded (`shading[33x25 grid, peak gain 4.94]`), applied at processing time, and
**not one direction was declared burnt out**. What the report now says about
highlights is `directionsWithUnmeasuredHighlights = 1` at
`worstUnmeasuredFraction = 0.000127` - three hundred and eighty pixels of three
million, which is a real specular glint rather than a window. 8 of 8 solved,
0.356 degrees of residual, 11.5 stops. No `slow frame` and no `converting took`
line either, so the camera path is quiet.

And the estimate learned, in one run:

```
estimate was 65 s and it took 21 s; work estimates run 0.64x next time
```

### The progress bars, and where their weights come from

Reported as "the progress bar doesn't work very well", on both the processing and
the capture screen. It was worse than that: it went backwards.

The pipeline reports a fraction that runs 0 to 1 **within** each stage - merging,
features, matching, aligning, blending - which is the right thing for it to
report and the wrong thing to hand to a bar. The service was passing each one
straight through as the overall figure, so over an eight minute stitch the bar
filled and reset four times, and the "about N minutes left" beside it reset with
it. The capture screen had the same shape across two phases: the sweep filled the
bar to a hundred percent and then the capture started it again at nothing.

`StageProgress` fixes the shape - each stage owns a share, a stage's own fraction
moves the bar only inside that share, and the result can never decrease. Never
decreasing is the part that matters: a bar that jumps forward has still told the
truth about what is done.

Every weight in it is measured.

**Processing.** The split between solving and writing comes from
`WorkEstimator`, the same calibrated estimator the size picker quotes its minutes
from - so it moves with the output size instead of being a constant that is wrong
at 8K and wrong the other way at 2K. The shares within the solve were measured by
running a real 34 direction bundle through the same pipeline with the stages
timed:

```
stage shares (of 13627 ms): merging 0.690 (9406 ms)  features 0.000 (0 ms)
                            matching 0.096 (1306 ms)  aligning 0.019 (261 ms)
                            blending 0.195 (2654 ms)
```

`features` gets nothing because it is not a stage: the features are extracted
inside the merging loop and the pipeline only marks the moment it finished, so
the merging share already contains it. Left in the list at zero rather than
omitted, so it reads as known rather than forgotten.

**Capture.** From the same real capture's log: the sweep ran 38.1 s
(12:42:05.955 to 12:42:44.091) and the capture 238.4 s (12:42:51.985 to
12:46:50.380). So the sweep is about 14% of the bar. A sweep ended early jumps
the bar forward to its full share, which is honest - that part is over.

### Eight seconds a frame, on the camera thread

This is the fault that made every capture on the phone impossible to finish, and
it took four wrong diagnoses to find. Written out in full because each wrong turn
was reasonable and each one cost a build, an install and somebody standing in a
room.

**What was actually happening.** The RAW converter applies the camera's lens
shading correction per pixel, through `ShadingMap.gainAt`. That call is two
floating point divisions, a `floor`, three nested calls and four array reads - and
a frame at 1/2 subsample is three million pixels. Measured on the phone by timing
the two halves of the loop separately:

```
convert breakdown: buffer reads 109 ms, shading lookups 8042 ms, 2000x1500
converting t18 b0 took 7774 ms on the camera thread
```

Eight seconds a frame, **on the camera handler thread** - the thread the next
frame of the burst has to arrive on. So a four rung bracket took thirty-two
seconds, outlived its own twelve second timeout, and the controller abandoned the
direction. Then the next, and the next.

**Why it appeared out of nowhere.** Until 2026-09-05 no capture had ever recorded
a shading map, because the map was being asked for on the still request while
being read from the preview probe (see below). Fixing that turned on a branch
that had never once run. The fix was right; the branch behind it was eight
seconds deep.

**What it was not.** Four measurements, each of which killed a theory:

| Theory | What settled it |
|---|---|
| RAW through the physical ultrawide does not deliver | It failed identically on the main lens |
| The ladder asked for exposures no hand can take | Reverting the handheld limit to 1/15 s changed nothing |
| `SENSOR_FRAME_DURATION` was below the stream's floor | Real, and worth fixing, and not this: the frames came back with exactly the exposure asked for |
| The camera was stalling | `burst rung 1 back at +7204 ms` with `got 33.3 ms` - the sensor was fine, the app was busy |

The one that found it was timing the two halves of the conversion loop. Nothing
else could have: from outside, "the camera is slow" and "we are slow between
frames" look identical.

**The fix.** None of that arithmetic needed to be per pixel. A frame's geometry is
fixed before the loop starts, so `ShadingMap.samplerFor` precomputes the grid
indices, the interpolation weights and the CFA plane once, and the inner loop is
four array reads and three multiply-adds - no division, no floor, no call. The
plane table is four entries, because that is all the CFA depends on; it was being
recomputed three million times to arrive at one of four answers.

Asserted **bit for bit** against `gainAt`, not approximately: a fast path that
differs in the last bits draws a faint grid over every frame.

```
before:  converting t18 b0 took 7774 ms
after:   converting t13 b0 took  215 ms
```

Thirty-eight times faster, and the capture that follows it:

```
burst rung 0 back at  +198 ms
burst rung 1 back at  +476 ms
burst rung 2 back at  +754 ms
burst rung 3 back at +1026 ms
direction 11 of 34 settled (11 shot, 0 given up) after 3363 ms
...
capture finished: 34 of 34 directions, 136 frames
```

A whole bracket in one second, a whole sphere with nothing given up - and decision
1 fired twice on the way, re-shooting two directions that came back burnt out.

**Still open**: 215 ms is 215 ms the camera thread is not listening. The
conversion belongs off that thread and across cores; it is fast enough now not to
break anything, which is a different thing from being right.

### The frame duration asked of the sensor

Found while looking for the above, real on its own, and not the cause.
`SENSOR_FRAME_DURATION` was set to the exposure time and nothing else, which for a
bracket is a demand: five rungs at 1/30 s asks for thirty frames a second while
the gain moves from ISO 29 to ISO 7276 between them. The device's own floor for
RAW at 4000x3000, read from `getOutputMinFrameDuration` and now logged at
configure time, is **33.3 ms - exactly 30 fps**. The shortest rung was asking for
9.3 ms, three times faster than the sensor is offered at any resolution.

The HAL complained in its own log, which is where the trail started:

```
CSIS Core context 0: LogicalChannel0LateConfigError
```

Now the requested duration is the longer of the exposure and the stream floor -
`StreamLadder.frameDurationFor`, which has the reasoning attached.

### A bench hatch that could not fire

`/data/local/tmp/hdri360-anyaim` disables the aim check so the burst path can be
exercised on a desk. It set the aim tolerance to 180 degrees and left **roll** at
30, and a phone lying still on a bench holds one roll angle while the plan wants
thirty-four different ones - so the shutter never came and the hatch looked broken
rather than partial. Both tolerances now. Without this none of the above could
have been measured without a person standing there for each attempt.

### Why a capture feels like it is getting slower, measured

His words after the 34 direction capture: the shots were "getting longer and
longer", and the last one took about fifteen seconds of holding still. The app's
own log agrees - directions 1 to 20 settled in about five seconds each, and the
last three took **15.8, 16.6 and 22.4 seconds**.

None of it is the camera. The frame timestamps in the same bundle put a whole
five rung bracket at **0.07 to 0.10 seconds** start to finish, and they do not
grow: first ten directions 0.06 s of burst, last ten 0.56 s. Nor is it the
exposures - the ladder's brightest rung was 1/15 s - and nor is it the ladder
growing, which happened twice in the whole capture and left the frame count at
four or five throughout.

Nor, it turns out, is it the distance turned. Walking the whole 34 direction plan
under the guide's own greedy nearest-first rule gives a worst single hop of 72
degrees and about 34 degrees of turning per direction, which is one frame
spacing. A first attempt at a cleverer order - detour up to 25 degrees to pick up
a direction whose neighbours are all shot, before it strands - made both numbers
*worse* (117 degrees worst hop, 4% more turning overall) and was reverted rather
than shipped with a story attached.

What the slow directions have in common is where they point. Reading the poses
out of the bundle:

| order | pitch | seconds |
|---|---|---|
| 28-31 | -54 deg | 8.2, 6.4, 5.5, 6.0 |
| 32 | -55 deg | **15.8** |
| 33 | -55 deg | **16.6** |
| 34 | -90 deg | **22.3** |

Every slow one is steeply **downward**, and the last is the nadir, straight at
the floor. The same downward ring taken earlier cost 5.5 to 8.2 seconds against
4.4 to 5.7 on the horizon, so pointing down is about a quarter slower in itself -
and being *last* multiplies it, because there is nothing left nearby to work
towards and the remaining direction has to be hunted for.

Roll is not the cause: the pole exemption is written on `abs(pitchDeg)` and so
already covers the nadir as well as the zenith.

What to do about it is a question about somebody's arms and not about geometry,
so it was asked rather than guessed - offer the awkward downward directions early,
drop the nadir altogether, or leave it alone. **Answer: leave the order alone.**
Nearest-first stays. The cost is known now and it is not a defect; a gap in the
floor would be.

### The shading map was never recorded, on any capture

`STATISTICS_LENS_SHADING_MAP_MODE_ON` was set in `applyManualSettings` - the still
path. The map is read in `whiteBalanceProbe`, which is attached to the **preview**
request, and that request went through `applyAutoSettings`, which did not ask for
it. So every capture the app has ever written logged `shading: none reported`, and
every bundle on disk is one the desktop probe cannot reproduce - which is the
entire reason the map is recorded. The colour matrix came through from the same
frame, which is why this was invisible: the probe was working, it was simply not
being asked the one extra question.

Fixed by asking on the probe request too. Not covered by a test: the request
builder is Android and the map only exists on a device. Needs a capture to confirm.

### Where the colour transform goes, and why it matters

Found by measurement on the first real capture, after the first attempt turned
the sphere magenta.

The transform must be applied to the **merged** radiance, never to a bracket's
rungs before they are merged. The merge decides which rungs to believe from the
pixel value itself - at `satHigh` a sample is on the rail and carries nothing -
and that test is only meaningful in the domain the sensor measures in, a
fraction of full well in [0,1]. A colour matrix leaves that domain: its diagonal
is well above one, so a channel comfortably below saturation lands above the
threshold after the transform, and a perfectly good sample is thrown away. Green
carries the largest coefficient on every phone matrix, so green loses its
brightest rungs first and comes back biased low.

Measured on the Pixel 9a: a neutral patch that merges to 35.93 / 35.92 / 35.91
in sensor space came out **35.76 / 11.88 / 27.06** with the matrix applied per
rung, and 35.94 / 35.91 / 35.90 with the same matrix applied after the merge.
Over a whole sphere that was a uniformly magenta panorama.

The same argument applies to the white balance gains, which had always been
applied per rung - mildly wrong for the same reason. Both are now one matrix,
applied once, in the right place. A side effect worth noting: the pose graph
improved from 42 solved pairs in 7 pieces to 55 in 4, because the feature search
had been looking at a magenta distortion of the radiance.

### What the ultrawide is actually worth

Decisions 5 to 7 were taken on the belief that the 9a's ultrawide would cut the
sphere "from 34 directions to roughly 15". Read off the device, it is better than
that guess in one way and worse in another, and it turned up a third thing nobody
had thought of.

`cameraIdList` gives 0 and 1. Camera 0 lists physical ids 2 and 3:

| | focal | h. FOV | sensor | tier |
|---|---|---|---|---|
| physical 2 | 4.53 mm | 70.5 deg | 4000x3000 | LINEAR_RAW |
| physical 3 | 1.84 mm | 104.2 deg | 4208x3120 | LINEAR_RAW |

Physical 2 **is** camera 0 - same focal length, same field of view, same sensor -
so a naive enumeration offers the main lens twice, once needing a physical stream
binding for no reason. Duplicates are collapsed on focal length and sensor
together, keeping the entry that opens without a binding.

Measured, not guessed:

  - **21 directions, not 15** - and 34 on the main lens, which is what the phone
    has actually been shooting.
  - **Better overlap at the same time**: the worst frame holds six partners at a
    quarter overlap against four on the main lens. Fewer frames and a better
    connected pose graph, which is not the trade anybody expected.
  - **Three stops more highlight headroom.** The ultrawide's shortest exposure is
    1/80463 s at base ISO 50 against the main lens's 1/17554 s at base ISO 29;
    allowing for f/2.2 against f/1.7, it can measure highlights **2.94 stops**
    brighter before clipping. That was not part of the decision and is the
    strongest argument in it: decision 3's "physically impossible" case - the sun
    in a window brighter than the shortest exposure - is three stops rarer on this
    lens. The capture that produced a magenta sphere clipped at 1/17554 s.

One thing had to be measured on the device rather than reasoned about: whether a
physical stream honours manual exposure, since the request goes to the logical
camera. It does - the app asked for 1/60 s at ISO 1351 and the camera reported
back exactly that - so no per-physical request keys are needed. Had it not, the
lens would have had to be offered at `MANUAL_YUV` or not at all.

### The first ultrawide sphere, and what the report was lying about

Shot on physical 3, processed on the phone, 2026-09-05.

| | main lens, 34 dirs | ultrawide, 21 dirs |
|---|---|---|
| capture phase | 246 s | **168 s** |
| frames | 137 | 93 |
| bundle residual | 1.299 deg | **0.980 deg** |
| directions the camera could not read | 4 | **0** |
| preview median R,G,B | 184 / 59 / 224 | **101 / 110 / 112** |

The three stops of extra headroom did exactly what the arithmetic said: nothing
in the scene was beyond the shortest exposure, so decision 3 never had to be
invoked. The colour is neutral off the phone rather than only in an offline
re-render. The growing ladder fired six times, all of them as run extensions into
rungs the ladder already had.

Two things this capture did *not* prove. Per direction it was slower - 7.2 s
against 8.0 s - because twenty-one directions over the same sphere means larger
turns between them; the win is in there being fewer of them, not in each being
quicker. And it did not exercise the roll tolerance at all: the ultrawide's plan
has rings at 0 and plus or minus 38 degrees, where the measured roll error was
1.7 to 5.4 degrees and would have passed the old 15 degree budget too. The 30
degrees cost nothing and remain unproven in the field.

### The report was calling a good sphere ruined

`clippedFraction` counted pixels in the finished panorama whose three channels all
exceeded 1.0. In a radiance map with an absolute scale that is very nearly all of
them: this whole, correct sphere reported **99.99% clipped**. A number wrong in
the safe direction would have been bad enough; that one was wrong in the direction
that makes a good capture look destroyed.

Whether anything clipped is a fact about the *merge* - a pixel no exposure in its
bracket held - and it is gone by the time a panorama exists. So it is taken there
instead, per direction, and the report says how many directions have unmeasured
highlights and how bad the worst is.

Cross-checked two ways on the same capture, which is why it can be believed: the
capture-time detection that drives the growing ladder named directions 18, 27, 28
and 33 as beyond the camera; re-deriving the number from the merge flags on the
desktop found exactly t017, t026, t027 and t032 - the same four, at 0.1% each.
Two independent paths, one answer.

### The sphere was levelled by one frame

Found while checking something else: the sun in the finished panorama sat 7 to 12
degrees below where the almanac puts it for the time and place.

Pairwise correspondences fix a sphere's shape and say nothing about its
orientation - rotate every frame by the same amount and every match is still a
match - so the global orientation has to come from the priors. The bundle
adjuster's answer was `fixFirst`, which put the whole sphere's idea of up on
**one** recorded device pose: an accelerometer reading taken while somebody held
a phone at arm's length.

Measured on the 34-direction capture:

  - the root frame's prior was tilted **11.01 degrees** from the consensus of all
    thirty-four
  - the tilt implied by individual frames ranged from **0 to 18.6 degrees**, so
    which frame won the spanning tree decided how level the sphere came out
  - applying the consensus alignment to that capture's own solved poses moves the
    sun from elevation 35.3 to **44.5**, into the almanac's 42 to 48 - and moves
    its azimuth by 1.8 degrees, because the correction is almost pure tilt

Two independent measurements, one number. For an HDRI a tilt is not cosmetic: it
is light arriving from the wrong elevation for as long as the file exists.

The fix is orthogonal Procrustes over every prior the connected component has -
`RotationAverage.align` - and **where** it happens is the part that is easy to
get wrong. The spanning tree chains what it can in the root's gauge and then
fills in what it could not reach from *those* frames' own priors, so the two sets
are in different frames of reference, differing by exactly the root's error.
Rotating the finished sphere moves the prior-placed frames off the very priors
they were placed on and leaves the disagreement where it was. The alignment has
to sit between chaining and filling, which is the only moment the component can
be brought into the priors' frame while the strays are not yet placed.

Still open: run offline through `:tools:probe`, the same bundle comes out with a
heading about forty degrees from the phone's. The probe solves the graph
differently - 55 pairs in four pieces against the phone's 62 - and heading is
arbitrary for lighting where tilt is not, so it is noted rather than chased.

### A hand cannot hold 1/15 s

`HANDHELD_LIMIT_SECONDS` is 1/15, and it is a policy number rather than a
hardware one: it decides when the planner stops buying exposure with time and
starts buying it with gain, on the grounds that motion blur cannot be undone and
noise partly can. Nobody had measured whether a hand actually holds 1/15 s.

Measured on the 34-direction bundle, comparing each rung against the rung below
it **on identical blocks** with the noise floor and the clipped highlights
excluded from both, so the comparison is about edges and not about how much of
the frame each rung threw away:

| rung reaching | directions | mean edge contrast | worst |
|---|---|---|---|
| 1/2340 s | 2 | 0.988 | 0.978 |
| 1/305 s | 17 | 0.949 | 0.779 |
| 1/40 s | 15 | 0.857 | 0.656 |
| 1/15 s | 5 | 0.808 | 0.587 |

Monotone with shutter, and every figure is a **lower bound** on the blur: the
long rungs are shot at higher ISO, and noise raises edge contrast, so the true
loss is worse than shown.

What makes it matter is the weighting. The merge is an inverse-variance mean and
weights a sample by its exposure squared, so at 1/15 s ISO 82 against 1/40 s ISO
29 the long rung carries roughly fifty times the weight - in the shadows, which
is the only place both are unsaturated, and which is exactly what the long rung
exists for. The smeared frame therefore dominates precisely where it is used, and
the merge has no idea it is smeared.

What shortening the limit would cost, for the ladder that capture actually used:

| limit | brightest rung | gain above base ISO 29 |
|---|---|---|
| 1/15 (now) | 1/15 s at ISO 82 | 1.5 stops |
| 1/60 | 1/60 s at ISO 328 | 3.5 stops |
| 1/125 | 1/125 s at ISO 683 | 4.6 stops |
| 1/250 | 1/250 s at ISO 1367 | 5.6 stops |

The headroom exists - the sensor goes to ISO 7276 - so this is a trade of shadow
noise against sharpness rather than a limit, and there is no knee to aim at:
0.032 of contrast per stop, all the way.

**18. The handheld limit is 1/30 s.** Chosen at the cautious end of that trade
rather than the middle: it removes about a fifth of the measured loss for one
stop of gain, taking the brightest rung of that capture from 1/15 s at ISO 82 to
1/30 s at ISO 164. 1/60 would have removed 37% for two stops. The reason for
stopping at one is that shadow noise is the thing the long rung exists to fight,
and buying sharpness with the very quantity the rung was added for is a poor
trade to make on one capture's worth of evidence.

The other half is still theory, and one attempt to measure it failed in a way
worth recording so nobody repeats it. Estimating noise from second differences
along a row - three samples two pixels apart, which cancels a linear ramp -
returned an SNR rising only 0.15 stops per stop of signal where a photon-limited
sensor gives 0.50, and a floor of 5% relative noise at half scale where a 12-bit
sensor should be near 0.5%. Both numbers say the estimator was measuring **scene
texture**, not noise: at any usable sampling stride, three samples across a
garden still straddle leaves and stone joints, and a second difference does not
cancel texture. The ISO 82 samples were also far too few to compare - 202 and
1253 against tens of thousands at base ISO.

What would actually answer it: flat sky, where there is no texture to confuse,
or a pair of frames of the same scene from a tripod at the two settings, where
the difference between them *is* the noise. Until then the cost side rests on
shot noise scaling as the square root of the photons - so 1/30 at double the gain
is half a stop of shadow SNR, and 1/60 would be a full stop - which is sound
physics and not a measurement of this sensor.

### A distortion coefficient at its own bound

The 34-direction sphere the phone produced was rendered with **k1 = 0.4000** -
`k1Limit` to the digit. The comment on that limit already said "phone lenses sit
well inside this and a runaway k1 is always a fit artefact", and the solver kept
it anyway.

It got past both existing gates honestly. The improvement gate compares the fit
with and without distortion on the same correspondences, and the fit really was
better; the signal gate asks whether the correspondences had the radial spread to
measure k1 at all, and a coefficient that large clears any threshold by being
large. Neither asks whether the optimiser stopped because it found a minimum or
because it ran out of room. A third gate now does, and the existing tests confirm
a genuine lens is still recovered to 0.09 against a truth of 0.09.

What such a k1 absorbs is pose or graph error. Bending every frame's geometry to
hide a residual is worse than leaving the residual where it can be seen.

**A correction to an earlier note in this file.** Fixing k1 by hand and
re-solving gave residuals of 1.05, 1.10, 1.05, 1.04, 1.05, 0.72, 0.89 and 0.84
degrees for k1 from 0 to 0.4, and it would be easy to read that as k1 being
unidentifiable. It is not evidence of that: the sweep set k1 in the *intrinsics*,
which changed how bearings were unprojected, which changed which pairs survived
RANSAC - so each residual was over a different set of measurements. In the
pipeline proper the pairs are solved once from undistorted bearings and only the
bundle adjustment sees k1, so its comparison is fair. What is real is milder: the
levelling change moved k1 from 0.2009 to 0.2607 on the same data, because
different starting rotations lead to a different local minimum.

### The report is version two now

`cdPerM2PerUnit` used to mean cd/m2 per *pipeline* unit and now means per *file*
unit, because the file is written in a stated unit rather than in the pipeline's
own. `clippedFraction` is gone, having counted pixels above 1.0 in a radiance map
and so called a whole correct sphere 99.99% clipped. `format` still said
`hdri360-report-1`.

A key whose meaning changes under a version that does not is the single failure a
format version exists to prevent, so the version moved: `hdri360-report-2`. The
pipeline suite now pins the version and the list of keys a later reader is
entitled to find, so the next change to what any of them mean has to come past a
failing test rather than through it.

### A figure in cd/m2 has to be a luminance

The statistics over the finished sphere - its extremes, its mean, its dynamic
range in stops - were taken over `(R + G + B) / 3`. That is the mean of three
channels and not the luminance of anything, and it did not much matter while the
file was in arbitrary units.

It matters now. The file is in kilocandela per square metre and the report
multiplies `stats.maxRadiance` by that unit to quote `maxLuminanceCdPerM2`, so
the figure had better be a luminance. For a saturated colour the two are far
apart: 100 units of pure green is 71.5 by Rec.709, and 33.3 by averaging. The
report was giving a confident answer in real units to a question nobody had
asked.

Now Rec.709 luma, which is what the file's own primaries define and what any
reader of the file will compute.

### Infinity in the file, and negatives before that

Two things the same measurement turned up, both now fixed and both worth writing
down because neither would ever be noticed by looking at a picture.

**Forty infinite pixels, all of them the sun.** Half float stops at 65504 and the
conversion overflowed to infinity above it - correct arithmetic, broken
environment map: an infinity is not a very bright pixel, it is a renderer that
returns NaN for everything the sun touches. Clamped in `ExrWriter.writable`, and
`Stats.clampedToHalf` counts it into the report so a number that changed between
the pipeline and the file is admitted rather than silent. The clamp sits at 65504
kilocandela - sixty-five million cd/m2, forty times the sun's own disc - so on any
real sphere it is zero.

**271,261 negative pixels** in the sphere written before the colour transform was
clamped. A colour matrix maps some real sensor colours outside the Rec.709 gamut
and the honest answer there is a negative number, which is a fine thing to carry
through a colour pipeline and a useless one in a radiance map, where it means a
light that removes light. Already fixed with the transform; the count is what
confirmed it - the sphere written after has none.

### The mirrored viewer, and why no test could have caught it

Reported on 2026-09-05: "all the HDRIs are mirrored left to right".

Everything in the chain from image to world is a proper rotation - `cameraToDevice`
is right-handed, `ANDROID_TO_WORLD` has determinant +1, the equirect projection
and its inverse agree - so nothing in it can mirror anything. Which is exactly
why the suite passed: every test compared the projection with its own inverse,
and a self-consistent pair agrees whichever way round it is.

Two questions settled it, and they had to be asked rather than derived:

  - Reading the finished panorama left to right is what you see turning to your
    **right** where you stood. So the file is correct.
  - It looked mirrored in the app's own viewer too. So the viewer disagreed with
    a file that was right.

The viewer built its ray as `vec3(ndc.x, ndc.y, 1)`, mapping screen-right onto
the world's +X. But +X is the viewer's **left**: the frame is right-handed with
+Y up and +Z the heading, so X = Y x Z points the other way. One negated
component.

The second half of the fix is the one that would have been missed. The drag was
`yaw -= dx`, and while the ray was mirrored the two wrongs cancelled - the
content followed the finger, so nothing pointed at the mirror. Fixing the ray
alone would have traded a mirrored sphere for a viewer that fights the hand.

What went in with it: the equirect suite now *states* the physical fact rather
than leaving it implied - the centre column is the heading, moving right turns
right, the top is overhead, and a frame's image-right lands right of centre. A
mirror is not catchable by comparing a pipeline with itself, so the ground truth
has to be written down where a test can hold it.

### The roll tolerance, measured rather than guessed

A 34 direction capture took 238 seconds, of which **7 seconds was shutter**. The
other 97% was aiming and waiting, and it got worse with pitch: 5.3 seconds median
near the horizon, 7.6 on the plus and minus 55 degree rings, 22 for the frame
pointing at the floor.

Measured from the poses the capture recorded:

| \|pitch\| | aim error (7 deg budget) | roll error (15 deg budget) |
|---|---|---|
| < 40 | median 0.39, max 0.65 | median 1.7, max 3.1 |
| 40-70 | median 0.24, max 0.77 | median 3.7, **max 9.3** |
| > 70 | median 0.45 | 51 and 77, where roll is free |

Aim is equally good at every pitch and never near its budget. Roll is the binding
constraint, and it grows with pitch because gravity pins pitch and roll against
itself and leaves the rotation *about* gravity - the heading - as the badly
determined one. A heading error of d lands in roll as d*sin(pitch): 31% of it at
18 degrees, 82% at 55. Dividing the measured roll error by sin(pitch) gives the
same 2 to 10 degrees at every ring, which is one heading uncertainty showing up in
different places. The two slowest directions of the 55 degree ring were exactly
the two with the largest roll error.

So what does roll actually cost? Nothing measurable. Rolling one frame of a real
plan while its neighbours stay put loses **0.00% of the sphere's coverage** out to
60 degrees, every frame keeps at least four partners at a quarter overlap, and the
descriptors go on matching out to 90 degrees of relative roll. Both measurements
are now in the suite - `capture-plan` and `features` - so the number cannot drift
back to a guess.

`rollToleranceDeg` is therefore **30**, not 15: two neighbours may be off in
opposite directions, so 30 each is the 60 that was measured safe, and it is more
than three times the worst roll a hand has actually produced. `freeRollAbovePitchDeg`
stays a step at 75, because at the poles roll is heading and nobody can see it.

Two things decision 1 changed that were not decided and are worth knowing about:

  - **The session header is rewritten during a capture.** It has to be: it is
    what a resumed capture comes back on, and the plan it describes now moves.
    Written into a part file and renamed, the same way the first one was, so a
    kill during the write leaves the whole old plan or the whole new one.
  - **Progress counts frames per direction rather than in total.** A re-shot
    direction writes over its own files, and counting every stored frame made a
    direction shot twice look like two directions' worth of progress. With a
    growing ladder a re-shoot is ordinary rather than rare.

## What the numbers looked like when this was written

A 34-direction capture, 128 raw frames, kept at `data/sphere-34` outside the
repo. 100% covered, 10.4 stops, and 21 matched pairs on the phone - where 34
frames need 33 edges before they are even connected.

Running it through `:tools:probe` after prior-based pair culling and a higher
corner count: 30 solved pairs, the graph in 12 pieces rather than 23, ten frames
still unpartnered. Those ten are the floor, the ceiling and the top ring, and a
measurement of the frames themselves ruled out both blur and lack of texture:
they are *clipped*. 82%, 78% and 52% of the green samples at the white level on
three of them. Hence decision 1.

A second bundle, `data/sphere-34b`, 137 frames, taken after those decisions were
in: **34 of 34 placed, 100% covered, 0.75 degrees of residual** through the same
`:tools:probe` - re-run at the end of the production pass to confirm nothing in
it broke the offline path. The graph is still in four pieces with 55 solved
pairs, which is the shading map that was never recorded and is now asked for on
the right request; that half is waiting on a capture to confirm.

The suite stands at 33 suites and 513,314 assertions.
