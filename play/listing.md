# Play Console listing — 360 HDRI Camera

Everything here is text to paste, plus the answers to the forms. The assets are
in this folder; the bundle is `app/build/outputs/bundle/release/app-release.aab`.

## Store listing

**App name** (30 max, 15 used)

```
360 HDRI Camera
```

**Short description** (80 max, 67 used)

```
A full 360° sphere in linear radiance, written as OpenEXR in cd/m².
```

**Full description** (4000 max)

```
Shoots a complete 360° sphere with your phone's camera and writes it as an
OpenEXR in linear Rec.709, with one unit in the file equal to 1000 cd/m². It is
a light meter that happens to take pictures: what comes out is a measurement you
can light a 3D scene with, not a photograph that looks like one.

HOW IT WORKS

Sweep the room once so the app can see how dark the darkest corner is and how
bright the brightest window. From that it plans one exposure ladder for the whole
sphere — every direction on the same radiance scale, which is the only way the
numbers mean anything across a seam. Then it guides you direction by direction:
aim, hold still, and it fires the whole bracket in about a second.

A direction that comes back burnt out is not left that way. The app adds a
shorter exposure and shoots that direction again, immediately, while you are
still pointing at it.

WHAT YOU GET

• An equirectangular OpenEXR, half float, up to 8192 × 4096
• Linear Rec.709, from your camera's own colour matrix and white balance
• A photometric scale from ISO 12232 saturation speed, so the file is in cd/m²
• A report beside it: dynamic range, coverage, residual, and every assumption
  the pipeline had to make, named
• Every frame kept as DNG, so a capture can be re-processed later

Nothing tone maps, gamma encodes or clips on the way through. Where a highlight
was brighter than the sensor could read, the report says so instead of pretending
otherwise.

LOOK AT IT ON THE PHONE

The viewer works in radiance, not in pixels: move the exposure through the whole
range and watch detail appear in a window that looked white. Turn the phone to
look around, or drag with a finger; pinch to zoom.

NO NETWORK

There is no internet permission in the manifest, so nothing can be sent
anywhere. No accounts, no analytics, no crash reporting. Your spheres stay on
your phone until you export one yourself.

WHAT IT NEEDS

A phone whose camera reports RAW with manual exposure — Camera2 FULL, which most
mid-range and better Android phones have had for years. Where a phone cannot do
that the app says so rather than producing numbers that look right.

FREE SOFTWARE

GNU General Public License v3 or later. The source is the authoritative answer to
any question about the method, and every published method the arithmetic comes
from is named inside the app, under About.
```

**Category**: Photography
**Tags**: Photography, Tools
**Contact email**: yours
**Privacy policy URL**: where you host `docs/privacy.md` — **required**, see below

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

Category: **Utility, Productivity, Communication or Other**. Every content
question is No — no violence, no sexuality, no profanity, no drugs, no gambling,
no user-generated content shared between users, no location sharing, no personal
information collected. Expected result: rated for everyone / PEGI 3.

## Target audience

- Age groups: 18+ (the simplest honest answer; nothing here is aimed at children)
- Does your app appeal to children? → No
- Ads: **No ads**

## App content, other declarations

- Government app: No
- Financial features: None
- Health: No
- Data deletion: no account, nothing to delete server-side
- Advertising ID: not used — the app declares no ads permission and no ad SDK
- News app: No
- COVID-19 contact tracing: No

## Assets in this folder

| What | File | Play's requirement |
|---|---|---|
| App icon | `icon-512.png` | 512 × 512, 32-bit PNG |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 × 500 |
| Phone screenshots | `screenshots/1-home.png` … `4-about.png` | 2 to 8, 1080 × 2424 here |
| The mark, editable | `logo.svg` | not uploaded; 108 × 108, the Android icon canvas 1:1 |

A fifth screenshot of the capture screen in use would be the most explanatory one
in the set, and it cannot be taken from a desk — it needs a phone actually being
aimed at something.

`logo.svg` is the mark as the app uses it, on a 108 × 108 canvas because that is
exactly the Android adaptive icon canvas: move a handle there and the `d`
attribute copies straight into `ic_launcher_foreground.xml` with no arithmetic in
between. The dashed circle is the safe zone — only the middle 72 units are
guaranteed to survive a launcher's crop. `icon-512.png` and the feature graphic
are both rendered from it.
