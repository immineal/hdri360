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

## Not now

**13. Release, Play Store and F-Droid wait** until the spheres are right.

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
