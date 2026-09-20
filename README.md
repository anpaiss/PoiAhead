# PoiAhead

Karoo extension that shows OpenStreetMap drinking water sources along your ride.

**Status: early MVP — not yet released.**

## Features

- **Popup alerts** while riding: "Water fountain in 250 m" with NAVIGATE / DISMISS
  buttons. NAVIGATE opens the native Karoo pin screen to detour to the fountain.
  The distance is live: it is recomputed at every GPS fix while the popup is on
  screen (25 s — 170 m of road at 25 km/h), rounded to 10 m (5 m below 100 m) so
  it stays readable, and once you are 20 m past the closest point reached it
  turns amber and reads "60 m · moving away".
- **Map icons**: water POIs rendered on the Karoo map (native water symbol).
- **Data page**: full-page field listing the 4 nearest water sources with distance.

All three surfaces can be toggled independently in the app settings.

## How it works

POIs (`amenity=drinking_water`) are fetched from the Overpass API along the
corridor of the active route (or around your position when riding without a
route) and cached locally for 30 days — so a previously visited area keeps
working with no connectivity. Ride with the phone paired at least once per area.

Alert trigger: a POI ahead requiring a detour smaller than the configured
threshold (50 / 100 / 200 m).

Water POIs come from three sources: the OSM cache, the POIs the active route
itself carries (the ones you placed on Komoot — they arrive with the Karoo's
own distances along the route) and the water POIs saved on your Karoo account.
Route and account POIs are already drawn by the Karoo, so only OSM ones go on
our map layer.

### "Ahead" is being switched from nearby to along-the-route — in the shadow

Today an alert is decided on the straight-line distance from the rider plus
the detour from the route polyline. That says nothing about whether a fountain
is still to come or already passed, and on an out-and-back the same fountain
is "close" on both legs. The replacement projects every POI onto the route
(`RouteProjection`, one pass per stretch of route that comes near it, so a loop
or an out-and-back yields one entry per pass) and takes the rider's position
along the route from the Karoo (`routeDistance - DISTANCE_TO_DESTINATION`,
invalid while off route or rejoining). "Next" is then simply the closest pass
still ahead.

This criterion runs **in the shadow**: it does not fire anything. Every tick
its decision and reason are logged next to the real one in the sample CSV
(`shadow_alert_id`, `shadow_reason`, `near_ahead_*`), and a `# shadow` marker
records every case where it would have alerted and the real logic did not (or
picked a different fountain). It is promoted only after at least 3 rides with a
route show the Karoo position is reliable (present on ≥ 95 % of on-route ticks,
no jumps > 500 m) and every real alert it classifies as *behind* the rider
counts as a false positive of the current logic. Until then the popup and the
page behave exactly as before. `eta_next_s` — the next fountain in seconds at
the ride's average speed — is logged for the same reason and not shown yet.

### Network

Overpass is reached through the Karoo's own HTTP channel first (WiFi when
connected, otherwise the companion app over BT — so a ride with the phone
paired is enough to download an area), with plain HTTP as fallback. The SDK
channel caps responses at 100 KB, so a query goes out as tiles of ~0.1° and
the log (`FETCH`) says which channel answered and how many bytes.

## Display refresh

The engine recomputes every 5 s, but the data page redraws only when a displayed line actually changes and only while it is the ride page on screen (on e-ink every redraw costs battery and leaves ghosting). The visibility gate fails open: if the active page is unknown, the page draws — a gate that wrongly filtered it out would freeze it for a whole ride and fail silently.

## Diagnostics

Two logs on the device. The human-readable one (1 MB, one `.old` generation):

```bash
adb pull /sdcard/Android/data/com.anpaiss.poiahead/files/poi_ahead.log
```

Tags: `ROUTE`, `GPS`, `POI`, `DIST`, `RIDE`, `FETCH`, `CACHE`, `ALERT` (one "skip" line per POI and reason, so a popup that did not fire can be explained), `POPUP`, `NAV`, `VIEW`, `TICK` (one timeline line per minute).

The structured one — one CSV row per 5 s tick while recording, 4 MB rotation:

```bash
adb pull /sdcard/Android/data/com.anpaiss.poiahead/files/poi_ahead_samples.csv
```

Each row carries our straight-line picture (nearest POI, its distance and detour, the alert decision and its reason) next to what the Karoo itself says about the ride: position along the route (`routeDistance - DISTANCE_TO_DESTINATION`), `ON_ROUTE`, rejoin state, and the number of POIs the route carries. Columns for values not produced yet (along-route candidate, shadow decision, ETA) are present and empty, so files recorded weeks apart stay comparable. `# ride`, `# route` and `# alert` marker lines separate rides and mark every popup. Numbers always use a dot as decimal separator, whatever the device locale.

## Releases & CI

Every push runs the unit tests and builds a debug APK (downloadable from the workflow run). Pushing a tag `vX.Y` builds a **signed** release APK and attaches it, with the Karoo extension manifest, to a GitHub release. **This repository is private, so the Karoo cannot fetch the manifest** (`MANIFEST_URL` gets a 404): installation stays manual via ADB. The wiring starts working by itself if the repository is ever made public.

Secrets for signing: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. `karoo-ext` comes from GitHub Packages: the workflow uses `GITHUB_TOKEN` and falls back to a `GPR_TOKEN` secret (PAT with `read:packages`); locally `gpr.user` / `gpr.key` in `local.properties` or `~/.gradle/gradle.properties`.

## Install

Sideload the APK (`adb install PoiAhead.apk`), open the app once to configure,
and grant the overlay permission for popup alerts. Add the "Water Ahead" field
to a ride profile page for the data page.

## Data

Water source data © OpenStreetMap contributors, via the Overpass API (ODbL).
