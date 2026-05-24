import NativeNearbyPlaces, { type NearbyPlace } from './NativeNearbyPlaces';

export type { Coords, SearchRegion, NearbyPlace } from './NativeNearbyPlaces';

/**
 * A subset of Apple's `MKPointOfInterestCategory`. v0.x covers the food
 * categories that motivated the module; non-food categories will be added
 * on demand. Strings match Apple's enum case names (without the
 * `MKPOICategory` prefix) for cross-walk clarity.
 *
 * @see https://developer.apple.com/documentation/mapkit/mkpointofinterestcategory
 */
export type POICategory =
  | 'restaurant'
  | 'cafe'
  | 'bakery'
  | 'brewery'
  | 'winery'
  | 'foodMarket'
  | 'nightlife';

export type SearchResultType = 'pointOfInterest' | 'address';

export type SearchRegionInput = {
  latitude: number;
  longitude: number;
  latitudeDelta: number;
  longitudeDelta: number;
};

export type SearchOptions = {
  query: string;
  region: SearchRegionInput;
  resultTypes?: SearchResultType[];
};

export type SearchNearbyOptions = {
  latitude: number;
  longitude: number;
  /** Search radius in meters. Apple caps at ~50,000m. Default `500`. */
  radiusMeters?: number;
  /**
   * Restrict to these POI categories. Omit (or empty array) to return
   * all supported categories.
   */
  categories?: POICategory[];
};

/**
 * Natural-language search within a region.
 *
 * - iOS: `MKLocalSearch` — full POI + address coverage.
 * - Android: `android.location.Geocoder.getFromLocationName(...)` —
 *   region-bounded text search. `resultTypes` is honoured client-side:
 *   results that look like pure addresses (no `featureName`, or a name
 *   equal to the street number/thoroughfare) are filtered out unless
 *   `'address'` is in `resultTypes`.
 *
 * @example
 * const places = await search({
 *   query: 'ramen',
 *   region: {
 *     latitude: 37.78,
 *     longitude: -122.42,
 *     latitudeDelta: 0.02,
 *     longitudeDelta: 0.02,
 *   },
 * });
 */
export async function search(options: SearchOptions): Promise<NearbyPlace[]> {
  return NativeNearbyPlaces.search(
    options.query,
    options.region.latitude,
    options.region.longitude,
    options.region.latitudeDelta,
    options.region.longitudeDelta,
    (options.resultTypes ?? []) as string[]
  );
}

/**
 * Search for nearby points of interest by category — no query required.
 *
 * - **iOS:** `MKLocalPointsOfInterestRequest` (iOS 14+) with the
 *   requested categories as the POI filter. No API key, free.
 * - **Android (with key):** Google Places API (New) — Nearby Search,
 *   Pro SKU. Single HTTP call with the union of all requested
 *   categories mapped to Places types (`restaurant`, `cafe`, `bakery`,
 *   `bar`, `liquor_store`, `supermarket`, `night_club`). Returns real
 *   POI data with `placeId` populated. Requires
 *   `com.google.android.geo.API_KEY` in the consumer's
 *   `AndroidManifest.xml`. See the README's Android Setup section.
 * - **Android (no key):** `Geocoder` fallback. Geocoder isn't POI-aware
 *   so category-style searches return ~0 results. Compiles + runs but
 *   not useful for actual nearby browsing; meant as a graceful
 *   degradation while consumers provision a key.
 *
 * @example
 * const restaurants = await searchNearby({
 *   latitude: 37.78,
 *   longitude: -122.42,
 *   radiusMeters: 500,
 *   categories: ['restaurant', 'cafe', 'bakery'],
 * });
 */
export async function searchNearby(
  options: SearchNearbyOptions
): Promise<NearbyPlace[]> {
  return NativeNearbyPlaces.searchNearby(
    options.latitude,
    options.longitude,
    options.radiusMeters ?? 500,
    (options.categories ?? []) as string[]
  );
}
