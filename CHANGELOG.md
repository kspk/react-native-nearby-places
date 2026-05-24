# Changelog

All notable changes to this project will be documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
[SemVer](https://semver.org/spec/v2.0.0.html).

## [0.3.0] — 2026-05-22

First public release. Earlier development iterations (`0.1.x`, `0.2.0`)
were never published to npm — they're noted below for historical context.

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
