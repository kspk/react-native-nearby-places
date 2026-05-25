# Changelog

All notable changes to this project will be documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
[SemVer](https://semver.org/spec/v2.0.0.html).

## [0.4.3] — 2026-05-25

### Changed
- CI workflow: bumped Node to `24` (was `22`) so the runner ships with a
  newer npm.
- CI workflow: switched provenance from a `npm publish --provenance`
  flag to the `NPM_CONFIG_PROVENANCE=true` env var — equivalent
  behaviour but doesn't require the flag to be passed at every call
  site.
- `package.json` `publishConfig`: added `{ "access": "public",
  "provenance": true }` so any future *local* `npm publish` invocations
  also default to provenance-on (CI was already doing it via env var;
  this makes manual publishes consistent).

### Notes
- `v0.4.2` was tagged but never reached npm — the CI publish failed
  during initial OIDC trusted-publisher debugging. `0.4.3` carries the
  same doc fixes as `0.4.2` (Geocoder mentions stripped) plus the
  workflow + publishConfig changes above.

## [0.4.2] — 2026-05-22

### Docs
- README "Why" section: removed lingering claim that Android falls back
  to Geocoder when no key is configured — that hasn't been true since
  0.4.0 (Geocoder was removed; no key now resolves to `[]`).
- `NearbyPlace.placeId` JSDoc: same correction — described the
  no-key case in terms of "result set is empty" rather than "Geocoder
  fallback".
- GitHub repo description updated to match.

No runtime changes. Doubles as the first CI-driven publish — kicked off
by tagging `v0.4.2`, picked up by `.github/workflows/publish.yml`,
authenticated via OIDC trusted publishing (no NPM_TOKEN).

## [0.4.1] — 2026-05-22

First published-to-npm release. No runtime changes from 0.4.0 — this is
a packaging-hygiene patch:

### Changed
- `package.json` `files` array tightened: was `["android"]`, now
  `["android/src", "android/build.gradle"]`. The wholesale `"android"`
  entry was pulling Gradle's `android/build/` output (compiled .class
  files, intermediate caches) into the npm tarball, ballooning the
  package from ~16 kB → ~352 kB compressed (1.5 MB unpacked).
- Added `.npmignore` as a belt-and-suspenders guard against future
  build artifacts sneaking back in.
- Added `CHANGELOG.md` to the `files` allowlist so the npm page shows
  release notes.
- Description now mentions Android (was iOS-only language from the
  0.1.x era).
- `android` and `google-places` added to keywords.

## [0.4.0] — 2026-05-22

### Removed
- **Geocoder fallback on Android.** Earlier versions backed
  `searchNearby` on `android.location.Geocoder` when no Places API key
  was configured. In practice Geocoder is an address service, not a POI
  index — it returned ~0 results for category browsing, leaving
  consumers with a misleading "API is working but empty" signal. Removed
  in favour of explicit semantics: no key configured →
  `searchNearby()` resolves with `[]`. `search()` (which only had the
  Geocoder Android path) now rejects with `E_NEARBY_PLACES_NOT_SUPPORTED`
  on Android until Places Text Search is wired.

### Changed
- Module size roughly halved. Removed `searchNearbyViaGeocoder`,
  `geocode` helper, `GeocodeCallback` interface, `serializeAddress`,
  `isAddressOnly`, `dedupeKey`, `CATEGORY_KEYWORDS`, `legacyExecutor`
  (renamed `networkExecutor` and now only used for the Places HTTP
  call). All Geocoder/Address imports dropped.
- README and JSDoc updated to drop the "fallback" narrative — Android
  setup section now describes the key as required rather than optional.

### Migration notes
- If you were relying on Geocoder text-query results via `search()`
  on Android: pin to `0.3.0`, or wait for Places Text Search.
- If you were relying on Geocoder category results via `searchNearby()`
  on Android: those were almost certainly empty already — no behavior
  change in practice. With a Places key configured, results improve
  dramatically.

## [0.3.0] — 2026-05-22

First public release on GitHub (tagged + pushed but never published
to npm). Earlier development iterations (`0.1.x`, `0.2.0`) were never
published either — they're noted below for historical context.

### Added
- **Android — Google Places API (New) backend** for `searchNearby`. Auto-
  detected at runtime: if the consumer's `AndroidManifest.xml` has
  `<meta-data android:name="com.google.android.geo.API_KEY" .../>`, the
  library issues a single HTTP call to
  `places.googleapis.com/v1/places:searchNearby` with a hard-coded
  Pro-SKU field mask. Otherwise the Geocoder fallback (introduced in
  0.2.0) stays active.
- `NearbyPlace.placeId?: string` — populated on the Android Places path
  with Google's `place_id`. Undefined on iOS and on Android's Geocoder
  fallback. Per Maps Platform ToS this field may be persisted
  indefinitely; everything else in `NearbyPlace` should be treated as
  session-scoped.
- README section covering Android setup (GCP project, key restriction,
  manifest meta-data), pricing model (Pro SKU ~$32/1k), and caching
  rules (§3.2.3(b) of the Maps Platform ToS).
- `PLACES_TYPE_MAP` mapping our category names to Google Places Table A
  types — `brewery → bar`, `winery → liquor_store`, `nightlife → bar +
  night_club`, etc. Best-effort umbrellas where 1:1 mapping isn't
  available.

### Changed
- Android `searchNearby` body refactored into per-backend helpers
  (`searchNearbyViaPlacesApi` / `searchNearbyViaGeocoder`) so the
  dispatch logic stays narrow.
- Field mask intentionally minimal — adding contact info would bump to
  Enterprise SKU ($40/1k), and ratings/photos to Enterprise Atmosphere
  ($50/1k). Documented in `PlacesApiClient.kt`.

### Notes
- Library still ships without any new Gradle dependencies. OkHttp is
  already on the React Native classpath.
- `search()` remains Geocoder-backed on Android — text-query search is a
  genuine fit for Geocoder, and Google's Autocomplete is a separate
  billing SKU (session tokens) intentionally out of scope for 0.3.0.

## [0.2.0] — internal, never published

### Added
- Android `searchNearby` and `search` backed by `android.location.Geocoder`.
  Async via `Geocoder.GeocodeListener` on API 33+, single-thread
  executor fallback on older devices. Category fan-out + dedup + radius
  filtering.

### Fixed
- Android build no longer fails with `Unresolved reference:
  NativeNearbyPlacesSpec`. Root cause: the library's
  `android/build.gradle` wasn't applying the React Native gradle plugin
  (`com.facebook.react`), so codegen never produced the spec class. Now
  applied when `newArchEnabled=true`.

### Known limitation
- Geocoder returns ~0 results for category keywords like
  `"restaurant"` — it's an address service, not a POI index. This is
  what motivated the 0.3.0 Places work.

## [0.1.0] — internal, never published

### Added
- Initial implementation. iOS-only.
- TurboModule spec (`NativeNearbyPlaces`) under new architecture
  (`newArchEnabled: true`), Codegen-driven.
- `search()` backed by `MKLocalSearch`.
- `searchNearby()` backed by `MKLocalPointsOfInterestRequest` (iOS 14+).
- Categories: `restaurant`, `cafe`, `bakery`, `brewery`, `winery`,
  `foodMarket`, `nightlife` — subset of `MKPointOfInterestCategory`.
- Android shipped as a rejecting stub (`E_NOT_SUPPORTED`) — replaced in
  0.2.0 by the Geocoder backend.
