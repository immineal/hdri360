# Play Console listing — 360 HDRI Camera

Everything here is text to paste, plus the answers to the forms. The assets are
in this folder; the bundle is `app/build/outputs/bundle/release/app-release.aab`.

The name is **360 HDRI Camera** everywhere: Play, the About screen, the lockup.
The mark draws the 360, so the words set beside it are only "HDRI Camera" — see
`logo-lockup.svg`. Never set the digits again in type next to the mark.

## Store listing

**App name** (30 max, 15 used)

```
360 HDRI Camera
```

**Short description** (80 max, 67 used)

```
A full 360° sphere in linear radiance, written as OpenEXR in cd/m².
```

**Full description** (4000 max, 2360 used)

```
Shoots a complete 360° sphere with your phone's camera and writes it as an OpenEXR in linear Rec.709, with one unit in the file equal to 1000 cd/m². It is a light meter that happens to take pictures: what comes out is a measurement you can light a 3D scene with, not a photograph that looks like one.

ONE EXPOSURE LADDER FOR THE WHOLE SPHERE

Sweep the room once so the app can see how dark the darkest corner is and how bright the brightest window. From that it plans one exposure ladder for the whole sphere, so every direction sits on the same radiance scale. That is the only way the numbers mean anything across a seam. Then it guides you direction by direction: aim, hold still, and it fires the whole bracket in about a second.

A direction that comes back burnt out gets shot again. The app adds a shorter exposure and fires immediately, while you are still pointing at it.

WHAT COMES OUT

• An equirectangular OpenEXR, half float, up to 8192 × 4096
• Linear Rec.709, from your camera's own colour matrix and white balance
• A photometric scale from ISO 12232 saturation speed, so the file is in cd/m²
• A report beside it: dynamic range, coverage, residual, and every assumption the pipeline had to make, named
• Every frame kept as DNG, so a capture can be re-processed later

Nothing tone maps, gamma encodes or clips on the way through. Where a highlight was brighter than the sensor could read, the report marks that direction as a lower bound.

LOOK AT IT ON THE PHONE

The viewer works in radiance. Move the exposure through the whole range and watch detail appear in a window that looked white. Turn the phone to look around, or drag with a finger; pinch to zoom.

NO NETWORK

There is no internet permission in the manifest, so nothing can be sent anywhere. No accounts, no analytics, no crash reporting. Your spheres stay on your phone until you export one yourself.

WHAT IT NEEDS

A phone whose camera reports RAW with manual exposure: Camera2 FULL, which most mid-range and better Android phones have had for years. Where a phone cannot do that, the app says so.

FREE SOFTWARE

GNU General Public License v3 or later. The source answers any question about the method, and every published method the arithmetic comes from is named inside the app, under About.
```

**Category**: Photography
**Tags**: Photography, Tools
**Contact email**: immineal@immineal.com
**Privacy policy URL**: https://immineal.github.io/hdri360/privacy.html
  (GitHub Pages, served from `docs/` on main; the page is `docs/privacy.html`,
  same words as `docs/privacy.md`.)

### What the text avoids, and why

The first draft leaned on one move over and over: state a thing by saying what it
is not. "Measured, not guessed", "not left that way", "instead of pretending
otherwise", "rather than producing numbers that look right", "not in pixels". One
such contrast carries the thesis; six read as a tic. Only the first survives.

The feature graphic had the same problem in picture form - a mark, then three
lines of type in three colours and three weights stacked under one another. It
now carries the lockup and nothing else.

## Assets in this folder

| What | File | Play's requirement |
|---|---|---|
| App icon | `icon-512.png` | 512 × 512, from `icon-store.svg` |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 × 500, from `feature-graphic.svg` |
| Lockup | `logo-lockup.svg` | not uploaded; the mark and the name in fixed proportion |
| Phone screenshots | `screenshots/1-home.png` … `4-about.png` | 2 to 8, 1080 × 2424 here |
| The mark, editable | `logo.svg` | 108 × 108, the Android adaptive icon canvas 1:1 |

`logo.svg` is the mark as the app uses it, on a 108 × 108 canvas because that is
exactly the Android adaptive icon canvas: move a handle there and the `d`
attribute copies straight into `ic_launcher_foreground.xml` with no arithmetic in
between. The dashed circle is the safe zone — only the middle 72 units are
guaranteed to survive a launcher's crop.

`icon-store.svg` is the same mark at scale 1.45. The Play icon is not an adaptive
icon, so the launcher's safe zone does not bind and the ring runs out to 81% of
the canvas instead of 56%.

### The panoramas in the screenshots are not real captures

Every sphere image in `screenshots/` is a placeholder from
[Poly Haven](https://polyhaven.com), CC0, composited into a fresh screencap from
the Pixel 9a. The owner's own captures — a house, a garden, rooms — must not go
to Google Play. The screens themselves are real; only the pixels that were his
photographs are replaced.

Which panorama sits where, so a re-shoot stays consistent:

| Where | Panorama |
|---|---|
| Feature graphic | `cobblestone_street_night` |
| Home, and library rows 1–3 | `lythwood_room`, `autoshop_01`, `st_fagans_interior` |
| Library rows 4–6 | `hansaplatz`, `abandoned_factory_canteen_01`, `brown_photostudio_02` |
| Viewer | `abandoned_factory_canteen_01` at 4k, the same sphere row 5 opens |

The build script lives in the session scratchpad, not the repo: it reads the
screencaps in `dev/`, finds each thumbnail box by its card grey, and paints the
panorama to fill it. Filling matters — letterboxing left a black bar above and
below every thumbnail, which the app never draws.

## Data safety form

The whole form is one answer repeated, and it is true because the manifest has no
`android.permission.INTERNET`:

- Does your app collect or share any of the required user data types? → **No**
- Is all of the user data collected by your app encrypted in transit? → n/a
- Do you provide a way for users to request that their data is deleted? → n/a

If it asks about photos specifically: the app creates photographs and keeps them
in its own private storage. It does not collect them, transmit them, or share
them. Deleting the app deletes them; Auto Backup is switched off.

## Content rating questionnaire

Category: **All Other App Types**. Every content question is No. Result as
submitted: PEGI 3, ESRB Everyone, ClassInd L, USK 0.

## Target audience

- Age groups: 18 and over
- Ads: **No ads**

## App content, other declarations

All submitted 2026-09-06:

- Privacy policy → the Pages URL above
- Sign in details → No, no part of the app is restricted
- Ads → No
- Content ratings → completed
- Target audience → 18 and over
- Data safety → no data collected, none shared
- Advertising ID → not used
- Government apps → No
- Financial features → none
- Health apps → none

## Where it stands

Submitted 2026-09-06: store listing, all nine declarations, app category and
contact details, and release **2 (1.0)** on the closed testing track "Alpha",
177 countries, full rollout. Google's stated turnaround is up to seven days.

Nothing is installable until that review passes - the opt-in link does not even
appear on the Testers tab before then - so the testers cannot start their
fourteen days early.

## What is still open

New personal developer accounts cannot publish straight to production: Play
requires a closed test with **12 testers opted in for 14 continuous days**, then
an application for production access. The account here was created 2026-09-03, so
this applies.

The email list "Closed test" exists and is selected on the track, and the change
is **saved but deliberately not submitted**: sending it while a review is running
cancels and restarts that review. The addresses go in together, once, when there
are enough of them.

Two things worth knowing when asking people:

  - Play counts **opted-in testers, not devices**. A tester has to open the link
    and accept; being on the list does nothing on its own. Leaving the programme
    drops the count and the fourteen days start again.
  - Ask more than twelve. Some never click the link.

Version code 1 was uploaded and then withdrawn when the foreground service came
out; a version code cannot be reused, so the shipped build is 2.
