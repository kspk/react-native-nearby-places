# react-native-nearby-places

Native nearby Point-of-Interest search for React Native. Built as a pure TurboModule — no Expo dependency.

| Platform | Backend | API key | Cost |
|---|---|---|---|
| iOS 15.1+ | [`MKLocalSearch`](https://developer.apple.com/documentation/mapkit/mklocalsearch) + [`MKLocalPointsOfInterestRequest`](https://developer.apple.com/documentation/mapkit/mklocalpointsofinterestrequest) | Not required | Free (Apple bundles MapKit with iOS) |
| Android (recommended) | [Google Places API (New)](https://developers.google.com/maps/documentation/places/web-service) — Nearby Search, Pro SKU | Required | ~$32 per 1k calls (Pro SKU) |
| Android (fallback) | [`android.location.Geocoder`](https://developer.android.com/reference/android/location/Geocoder) | Not required | Free, but ~0 results for category-style searches (Geocoder is an address service, not a POI index) |

The Android backend auto-selects: if the consumer's `AndroidManifest.xml` has `<meta-data android:name="com.google.android.geo.API_KEY" .../>`, Places is used. Otherwise the library falls back to Geocoder so the build doesn't crash.

## Why

Commercial places APIs (Foursquare, Yelp) cost money and require a backend proxy for key safety. Apple's MapKit ships with iOS and surfaces a curated POI database that's strong for established businesses — good enough for "what restaurants are near me right now?" without writing a backend. Android doesn't have an equivalent on-device POI index (Geocoder is address-only, not POIs), so the cleanest path is Google Places API (New) at the consumer's expense, with a graceful Geocoder fallback when no key is configured.

## Requirements

- React Native **0.74+** with new architecture enabled (`newArchEnabled: true`).
- iOS **15.1+**.
- Android **API 23+** (`minSdkVersion 23`).

This module is **new-architecture only by design** — TurboModule with Codegen, no bridge interop fallback. Make sure your app is on new arch before installing.

## Install

```sh
npm install react-native-nearby-places
cd ios && pod install   # iOS only
```

Then rebuild your native app (Xcode / `expo run:ios` / `expo run:android`).

## Android Setup (for real POI data)

The library compiles + ships on Android without any setup, but `searchNearby` will return ~0 results because the Geocoder fallback isn't POI-aware. To get real data:

### 1. Provision a Google Cloud project + Places API key

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create a project (or pick an existing one).
2. In the project, enable **Places API (New)** under APIs & Services → Library. (The "New" qualifier matters — the legacy Places API has a different pricing model and we hit the new REST endpoint.)
3. Under APIs & Services → Credentials → Create credentials → API key, create a key.
4. Click the new key to restrict it:
   - **Application restrictions** → Android apps → add your app's package name and SHA-1 signing-certificate fingerprint. (Get the SHA-1 from `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android` for debug builds, or from your release keystore for production.)
   - **API restrictions** → Restrict key → check **Places API (New)**.
5. Copy the key string.

### 2. Add the key to your app's `AndroidManifest.xml`

Inside `<application>`:

```xml
<meta-data
  android:name="com.google.android.geo.API_KEY"
  android:value="YOUR_API_KEY_HERE" />
```

If you're using Expo's prebuild, you can wire this through `app.json` with an `expo-build-properties`-style plugin or via the [`googleMapsApiKey`](https://docs.expo.dev/versions/latest/config/app/#googlemapsapikey) field under `android.config`. Either way, the meta-data name above is what the library reads from at runtime.

### 3. Rebuild

```sh
npx expo run:android   # or react-native run-android
```

That's it — `searchNearby` will now hit Google Places.

### Pricing notes

- The field mask is hard-coded to `places.id, places.displayName, places.location, places.shortFormattedAddress` — all **Pro SKU** fields. As of late 2025 that's **$32 per 1,000 calls**. Adding contact fields would bump to Enterprise ($40/1k); adding ratings/photos would bump to Enterprise Atmosphere ($50/1k).
- Google removed the universal $200 monthly Maps credit in March 2025. Existing projects may be grandfathered; new projects pay from call #1.
- **You cannot cache the response** beyond `places.id` and the corresponding `(lat, lng)` (the latter for up to 30 days). See the [Maps Platform ToS §3.2.3(b)](https://cloud.google.com/maps-platform/terms/maps-service-terms) and the [Places API caching policy](https://developers.google.com/maps/documentation/places/web-service/policies). To stay ToS-clean, treat the returned name/address as session-scoped — only persist `placeId` if you need a durable handle, and let users re-confirm names on save (Pattern B).

## API

### `search(options): Promise<NearbyPlace[]>`

Natural-language text search within a region. iOS uses `MKLocalSearch`; Android uses Geocoder's `getFromLocationName` (which is a genuine fit for text queries — it's the category-browse case where Geocoder falls short).

```ts
import { search } from 'react-native-nearby-places';

const results = await search({
  query: 'ramen',
  region: {
    latitude: 37.78,
    longitude: -122.42,
    latitudeDelta: 0.02,   // ≈ 2km
    longitudeDelta: 0.02,
  },
  resultTypes: ['pointOfInterest'], // default; can include 'address'
});
```

### `searchNearby(options): Promise<NearbyPlace[]>`

Category POI search — no query. iOS uses `MKLocalPointsOfInterestRequest`; Android uses Google Places (or Geocoder fallback).

```ts
import { searchNearby } from 'react-native-nearby-places';

const restaurants = await searchNearby({
  latitude: 37.78,
  longitude: -122.42,
  radiusMeters: 500,                       // default 500, max 50000
  categories: ['restaurant', 'cafe'],      // omit for all supported categories
});
```

### `NearbyPlace`

```ts
type NearbyPlace = {
  placeId?: string;            // Google place_id (Android Places only)
  name?: string;
  phoneNumber?: string;        // iOS only currently
  url?: string;                // iOS only currently
  category?: POICategory;
  coordinate: { latitude: number; longitude: number };
  thoroughfare?: string;
  subThoroughfare?: string;
  locality?: string;          // city
  subLocality?: string;       // neighborhood
  administrativeArea?: string; // state
  postalCode?: string;
  isoCountryCode?: string;
  country?: string;
};
```

All fields except `coordinate` are optional. Per-platform coverage varies — see the table below.

| Field | iOS (MapKit) | Android (Places) | Android (Geocoder fallback) |
|---|:-:|:-:|:-:|
| `placeId` | — | ✓ | — |
| `name` | ✓ | ✓ | sometimes |
| `coordinate` | ✓ | ✓ | ✓ |
| `category` | ✓ | best-effort tag | best-effort tag |
| `thoroughfare` / address parts | ✓ | `shortFormattedAddress` → `thoroughfare` only | ✓ |
| `phoneNumber`, `url` | ✓ | — (would bump to Enterprise SKU) | — |

### Supported categories

```
restaurant, cafe, bakery, brewery, winery, foodMarket, nightlife
```

Mapped per-platform:
- iOS → `MKPointOfInterestCategory` values
- Android Places → `restaurant`, `cafe`, `bakery`, `bar`, `liquor_store`, `supermarket`, `night_club` (umbrellas; `brewery`/`winery` don't have separate Places types)
- Android Geocoder → English keywords (`"restaurant"`, `"cafe"`, etc — limited utility)

## Pairing with current location

This library only does **POI lookup** given coordinates. It does not ask the OS for the user's current location — that's a different concern (permission-gated, privacy-sensitive). The typical pairing in a React Native app:

```ts
// expo-location (Expo apps) or react-native-geolocation-service (bare RN)
const coords = await getCurrentLocation();

const nearby = await searchNearby({
  latitude: coords.latitude,
  longitude: coords.longitude,
  radiusMeters: 500,
  categories: ['restaurant', 'cafe'],
});
```

## Caveats

- **iOS long-tail coverage is uneven.** Apple's database is curated, not crowdsourced. Major restaurants are well-represented; food trucks at farmers' markets, brand-new openings, and indie restaurants in non-major-metro areas may be missing. Pair with another source if you need full long-tail coverage.
- **No reviews or ratings on either platform.** Adding them on Android would bump to the Enterprise Atmosphere SKU; out of scope for v0.x.
- **Android without an API key returns ~0 results for category-style searches.** Geocoder is an address service, not a POI index. The fallback exists so your build doesn't crash, not so your users get useful results.
- **Don't persist Places content beyond `placeId` + `(lat, lng)`** — see the pricing/caching note in the Android Setup section above.

## License

MIT
