# Privacy policy — 360 HDRI Camera

Last updated 2026-09-06.

## The short version

This app collects nothing, sends nothing and has no account. Everything it makes
stays on the phone until you move it yourself.

That is not a promise about intent, it is a property of the build: the app
declares **no network permission** in its manifest, so it cannot open a network
connection at all. Android will not let it. Every claim below follows from that.

## What the app stores, and where

Captures live in the app's own private storage, where only this app can read
them. Each one holds:

- the raw frames as DNG files, and the app's own working copies of them;
- a record of each frame — the exposure it was shot at and the direction the
  phone was pointing, from the phone's own rotation sensor;
- the finished panorama, its preview image, and a report of what was measured;
- whatever you chose to call the sphere;
- a short capture log, kept so a capture that goes wrong can be explained. It is
  a text file in the same private storage, it is capped in size, and it never
  leaves the phone.

Uninstalling the app deletes all of it. Automatic backup is switched off, so
none of it is copied to a cloud backup either.

## What is not collected

No analytics, no crash reporting, no advertising identifier, no device
identifier, no contacts, no location. The app requests no location permission
and reads no location data; the direction a frame was shot in is a compass and
gyroscope reading relative to the room, not a position on the earth.

## Permissions, and why each one is there

- **Camera** — to take the photographs. This is the app.
- **Foreground service** and **data sync** — stitching a sphere takes minutes,
  and this keeps the work alive while it runs.
- **Wake lock** — so that work does not stall when the screen goes off.
- **Notifications** — to show the progress of that work. Declining it costs you
  the notification and nothing else.
- **Vibrate** — the zenith is shot with the screen facing away from you, where a
  buzz is the only feedback you can use.

There is deliberately no internet permission and no storage permission.

## What leaves the phone, and only when you say so

Exporting a sphere writes an OpenEXR file into your Downloads folder, through
Android's own media store. That is a file you can then do anything with — it is
the point of the app. Nothing else copies anything anywhere, and the app has no
way to.

## Children

The app has no accounts, no messaging, no user-generated content shared with
anyone, and no data collection, so there is nothing here that treats children
differently from anyone else.

## Changes

If this ever changes — if some future version needs the network for something —
it will need a new permission, which Android will show you at install or update
time, and this document will say what it is used for before that version ships.

## Contact

The app is free software under the GNU General Public License v3. The source is
the authoritative answer to any question on this page: if the manifest has no
`android.permission.INTERNET` in it, nothing above can be false.
