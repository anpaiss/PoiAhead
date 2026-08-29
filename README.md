# PoiAhead

Karoo extension that shows OpenStreetMap drinking water sources along your ride.

**Status: early MVP — not yet released.**

## Features

- **Popup alerts** while riding: "Water fountain in 250 m" with NAVIGATE / DISMISS
  buttons. NAVIGATE opens the native Karoo pin screen to detour to the fountain.
- **Map icons**: water POIs rendered on the Karoo map (native water symbol).
- **Data page**: full-page field listing the 4 nearest water sources with distance.

All three surfaces can be toggled independently in the app settings.

## How it works

POIs (`amenity=drinking_water`) are fetched from the Overpass API along the
corridor of the active route (or around your position when riding without a
route) and cached locally for 30 days — so a previously visited area keeps
working with no connectivity. The Karoo only has internet while tethered to the
companion app; ride with the phone connected at least once per area.

Alert trigger: a POI ahead requiring a detour smaller than the configured
threshold (50 / 100 / 200 m).

## Install

Sideload the APK (`adb install PoiAhead.apk`), open the app once to configure,
and grant the overlay permission for popup alerts. Add the "Water Ahead" field
to a ride profile page for the data page.

## Data

Water source data © OpenStreetMap contributors, via the Overpass API (ODbL).
