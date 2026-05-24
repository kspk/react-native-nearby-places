/**
 * TurboModule spec — read by React Native's Codegen at build time to
 * generate native binding code.
 *
 * Method arguments are kept primitive-only on purpose: Codegen's handling
 * of struct args is uneven across RN versions. The consumer-facing
 * wrapper in `index.ts` accepts richer object inputs and unpacks them
 * here. Return types still use rich objects — Codegen handles those
 * fine as NSDictionary / WritableMap.
 *
 * Codegen constraints:
 *  - Module name (`'NativeNearbyPlaces'`) must match the native
 *    registration on both platforms
 *  - The default export must be `TurboModuleRegistry.getEnforcing<Spec>(...)`
 *  - Only RN-recognised TS shapes inside the `Spec` interface
 */
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type Coords = {
  latitude: number;
  longitude: number;
};

export type SearchRegion = {
  latitude: number;
  longitude: number;
  latitudeDelta: number;
  longitudeDelta: number;
};

export type NearbyPlace = {
  /**
   * Opaque external identifier for the place. Populated on Android when
   * backed by Google Places (it's the Google `place_id`). Undefined on
   * iOS (MapKit has no stable POI identifier in our consumed surface)
   * and on Android's Geocoder fallback. Per the Google Maps Platform
   * ToS, this value may be persisted indefinitely; everything else here
   * should be treated as session-scoped and re-fetched if needed.
   */
  placeId?: string;
  name?: string;
  phoneNumber?: string;
  url?: string;
  category?: string;
  coordinate: Coords;
  thoroughfare?: string;
  subThoroughfare?: string;
  locality?: string;
  subLocality?: string;
  administrativeArea?: string;
  postalCode?: string;
  isoCountryCode?: string;
  country?: string;
};

export interface Spec extends TurboModule {
  /**
   * Natural-language search. Codegen-friendly primitive signature.
   * @param resultTypes Array of `'pointOfInterest' | 'address'`. Empty
   *                    array means default (pointOfInterest only).
   */
  search(
    query: string,
    centerLatitude: number,
    centerLongitude: number,
    latitudeDelta: number,
    longitudeDelta: number,
    resultTypes: string[]
  ): Promise<NearbyPlace[]>;

  /**
   * Category-only nearby POI search. Codegen-friendly primitive signature.
   * @param categories Array of POI category strings. Empty array means
   *                   all supported categories.
   */
  searchNearby(
    latitude: number,
    longitude: number,
    radiusMeters: number,
    categories: string[]
  ): Promise<NearbyPlace[]>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeNearbyPlaces');
