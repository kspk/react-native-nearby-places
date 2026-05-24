package com.reactnativenearbyplaces

import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Android implementation. Backed by `android.location.Geocoder` — Android's
 * built-in geocoding service. Like `MKLocalSearch` on iOS, it ships with
 * the OS and needs no API key. Unlike MapKit it isn't POI-aware, so
 * `searchNearby(categories)` works by translating each requested category
 * into an English keyword and issuing a region-bounded text search per
 * category, then merging + deduping. The returned `category` field is
 * tagged from the requesting category (best-effort).
 *
 * Threading: `Geocoder.getFromLocationName(...)` is blocking and was
 * deprecated for sync use in API 33. We use the async `GeocodeListener`
 * on 33+ and run the blocking call on a single-thread executor on older
 * devices.
 */
@ReactModule(name = ReactNativeNearbyPlacesModule.NAME)
class ReactNativeNearbyPlacesModule(reactContext: ReactApplicationContext) :
  NativeNearbyPlacesSpec(reactContext) {

  /**
   * One worker is plenty — calls are user-initiated, not high-frequency,
   * and the OS-level geocoder serialises requests internally anyway.
   */
  private val legacyExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "RNNP-geocoder").apply { isDaemon = true }
  }

  override fun getName(): String = NAME

  override fun search(
    query: String,
    centerLatitude: Double,
    centerLongitude: Double,
    latitudeDelta: Double,
    longitudeDelta: Double,
    resultTypes: ReadableArray,
    promise: Promise
  ) {
    if (!Geocoder.isPresent()) {
      promise.reject(E_UNAVAILABLE, GEOCODER_UNAVAILABLE_MSG)
      return
    }

    // JS region is centre + deltas; Geocoder wants a bounding box (LL, UR).
    val halfLat = latitudeDelta / 2.0
    val halfLng = longitudeDelta / 2.0
    val llLat = centerLatitude - halfLat
    val llLng = centerLongitude - halfLng
    val urLat = centerLatitude + halfLat
    val urLng = centerLongitude + halfLng

    // resultTypes is iOS-specific (MKLocalSearchResultType). Honour
    // semantically: empty / `pointOfInterest` → POI-only (filter out
    // address-only results); `address` present → include addresses too.
    val includeAddresses = resultTypes.toStringList().contains("address")

    geocode(query, MAX_RESULTS_SEARCH, llLat, llLng, urLat, urLng, object : GeocodeCallback {
      override fun onResults(addresses: List<Address>) {
        val filtered = if (includeAddresses) addresses else addresses.filter { !isAddressOnly(it) }
        val out = Arguments.createArray()
        for (a in filtered) out.pushMap(serializeAddress(a, category = null))
        promise.resolve(out)
      }
      override fun onError(error: Throwable) {
        promise.reject(E_SEARCH, error.localizedMessage ?: "Geocoder search failed.", error)
      }
    })
  }

  override fun searchNearby(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    categories: ReadableArray,
    promise: Promise
  ) {
    Log.d(TAG, "searchNearby called: lat=$latitude lng=$longitude r=$radiusMeters cats=${categories.toStringList()}")

    // Provider selection: prefer Google Places (real POI data) when the
    // consumer has provisioned an API key. Otherwise fall back to
    // Geocoder, which compiles + runs but returns ~0 results for category
    // keywords because it's an address service, not a POI index.
    val apiKey = getGooglePlacesApiKey()
    if (apiKey != null) {
      Log.d(TAG, "Provider: Google Places API")
      searchNearbyViaPlacesApi(latitude, longitude, radiusMeters, categories, apiKey, promise)
      return
    }

    Log.d(TAG, "Provider: Geocoder (no API key configured)")
    Log.d(TAG, "Geocoder.isPresent=${Geocoder.isPresent()}")
    if (!Geocoder.isPresent()) {
      promise.reject(E_UNAVAILABLE, GEOCODER_UNAVAILABLE_MSG)
      return
    }
    searchNearbyViaGeocoder(latitude, longitude, radiusMeters, categories, promise)
  }

  /**
   * Google Places API (New) — Nearby Search. Pro SKU. Single HTTP call
   * with the union of all requested categories as `includedTypes`.
   */
  private fun searchNearbyViaPlacesApi(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    categories: ReadableArray,
    apiKey: String,
    promise: Promise,
  ) {
    val requested = categories.toStringList().ifEmpty { CATEGORY_KEYWORDS.keys.toList() }
    val placesTypes = requested
      .flatMap { name -> PLACES_TYPE_MAP[name] ?: emptyList() }
      .distinct()
    if (placesTypes.isEmpty()) {
      promise.resolve(Arguments.createArray())
      return
    }
    // Pick a "primary" category per Places type for tagging back to the
    // caller. When multiple ForkLog categories map to the same Places
    // type (e.g. brewery + nightlife → bar), the first-listed wins. Best
    // effort — Places doesn't return category in this field mask.
    val typeToCategory: Map<String, String> = PLACES_TYPE_MAP.entries
      .flatMap { (cat, types) -> types.map { it to cat } }
      .groupBy({ it.first }, { it.second })
      .mapValues { it.value.first() }

    legacyExecutor.execute {
      try {
        val client = PlacesApiClient(apiKey)
        val results = client.searchNearby(latitude, longitude, radiusMeters, placesTypes)
        Log.d(TAG, "Places API returned ${results.size} results")
        val out = Arguments.createArray()
        for (r in results) {
          // We don't know which Places type each result actually matched
          // against (the response doesn't carry that with this mask), so
          // we tag with the *first* category that asked for any type.
          val cat = typeToCategory.values.firstOrNull { it in requested } ?: requested.firstOrNull()
          out.pushMap(serializePlaceResult(r, category = cat))
        }
        promise.resolve(out)
      } catch (e: Exception) {
        Log.w(TAG, "Places API call failed: ${e.message}", e)
        promise.reject(E_NEARBY, e.localizedMessage ?: "Places API call failed.", e)
      }
    }
  }

  /**
   * Fallback path — Android's built-in `Geocoder`. Address-only by
   * design; category browsing yields sparse results. Kept around so
   * consumers without an API key still get *something* (and `search()`
   * with a text query still works well).
   */
  private fun searchNearbyViaGeocoder(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    categories: ReadableArray,
    promise: Promise,
  ) {
    val radius = min(max(radiusMeters, 1.0), 50_000.0)

    // Bounding box around the centre, sized to the requested radius. Lat
    // is uniform (~111 km/deg); lng compresses with latitude.
    val latDelta = radius / 111_320.0
    val lngDelta = radius / (111_320.0 * max(cos(Math.toRadians(latitude)), 1e-6))
    val llLat = latitude - latDelta
    val llLng = longitude - lngDelta
    val urLat = latitude + latDelta
    val urLng = longitude + lngDelta

    // Empty categories means "all supported" — mirrors the iOS default
    // where omitting `pointOfInterestFilter` means "all categories".
    val requested = categories.toStringList().ifEmpty { CATEGORY_KEYWORDS.keys.toList() }
    val resolved: List<Pair<String, String>> = requested.mapNotNull { name ->
      CATEGORY_KEYWORDS[name]?.let { keyword -> name to keyword }
    }
    if (resolved.isEmpty()) {
      promise.resolve(Arguments.createArray())
      return
    }

    // Dedupe across category fan-out by (name, lat≈5dp, lng≈5dp).
    val seen = ConcurrentHashMap.newKeySet<String>()
    val merged = mutableListOf<WritableMap>()
    val mergedLock = Any()
    val pending = AtomicInteger(resolved.size)
    val firstError = AtomicReference<Throwable?>(null)

    fun finishIfDone() {
      if (pending.decrementAndGet() != 0) return
      Log.d(TAG, "searchNearby finishing: merged=${merged.size} firstError=${firstError.get()?.message}")
      val err = firstError.get()
      if (err != null && merged.isEmpty()) {
        // Only surface the error when there's literally nothing to return —
        // partial success across categories is more useful than nothing.
        promise.reject(E_NEARBY, err.localizedMessage ?: "Geocoder searchNearby failed.", err)
        return
      }
      val out = Arguments.createArray()
      synchronized(mergedLock) { for (m in merged) out.pushMap(m) }
      promise.resolve(out)
    }

    Log.d(TAG, "bbox: ($llLat,$llLng)-($urLat,$urLng) resolved=${resolved.size} keywords=${resolved.map { it.second }}")
    for ((catName, keyword) in resolved) {
      geocode(keyword, MAX_RESULTS_PER_CATEGORY, llLat, llLng, urLat, urLng, object : GeocodeCallback {
        override fun onResults(addresses: List<Address>) {
          Log.d(TAG, "Geocoder[$catName/$keyword] returned ${addresses.size} addresses")
          for (a in addresses) {
            if (!a.hasLatitude() || !a.hasLongitude()) continue
            val d = distanceMeters(latitude, longitude, a.latitude, a.longitude)
            if (d > radius) continue
            val key = dedupeKey(a)
            if (!seen.add(key)) continue
            synchronized(mergedLock) { merged.add(serializeAddress(a, category = catName)) }
          }
          finishIfDone()
        }
        override fun onError(error: Throwable) {
          Log.w(TAG, "Geocoder[$catName/$keyword] error: ${error.message}", error)
          firstError.compareAndSet(null, error)
          finishIfDone()
        }
      })
    }
  }

  /**
   * Reads `com.google.android.geo.API_KEY` from the host app's manifest
   * `<meta-data>`. Returns `null` if the tag isn't present or is blank —
   * we treat absence as "use Geocoder fallback", not as an error. This
   * is the same meta-data name the Google Maps SDK reads from, so a
   * consumer that already uses Maps doesn't need a separate key.
   */
  private fun getGooglePlacesApiKey(): String? {
    return try {
      val ctx = reactApplicationContext
      val pm = ctx.packageManager
      val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(
          ctx.packageName,
          PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
      } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
      }
      appInfo.metaData?.getString("com.google.android.geo.API_KEY")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read API key from manifest: ${e.message}")
      null
    }
  }

  private fun serializePlaceResult(r: PlacesApiClient.PlaceResult, category: String?): WritableMap {
    val m = Arguments.createMap()
    m.putString("placeId", r.id)
    r.name?.let { m.putString("name", it) }
    if (category != null) m.putString("category", category)

    val coord = Arguments.createMap()
    coord.putDouble("latitude", r.latitude)
    coord.putDouble("longitude", r.longitude)
    m.putMap("coordinate", coord)

    // `shortFormattedAddress` is closest to the picker's "meta line"
    // expectation — drop it on `thoroughfare` so the JS-side consumer
    // (PlacePickerSheet) renders it without further branching.
    r.address?.let { m.putString("thoroughfare", it) }
    return m
  }

  // ── helpers ───────────────────────────────────────────────────────────

  /** Internal callback shape that hides the API 33 split. */
  private interface GeocodeCallback {
    fun onResults(addresses: List<Address>)
    fun onError(error: Throwable)
  }

  /**
   * Region-bounded text search via Geocoder. On API 33+ this is async via
   * `GeocodeListener`; on older devices we run the deprecated blocking
   * call on `legacyExecutor` and adapt the result to the same callback.
   */
  private fun geocode(
    query: String,
    maxResults: Int,
    llLat: Double, llLng: Double,
    urLat: Double, urLng: Double,
    cb: GeocodeCallback
  ) {
    val geocoder = Geocoder(reactApplicationContext)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      geocoder.getFromLocationName(
        query, maxResults,
        llLat, llLng, urLat, urLng,
        object : Geocoder.GeocodeListener {
          override fun onGeocode(addresses: MutableList<Address>) = cb.onResults(addresses)
          override fun onError(errorMessage: String?) =
            cb.onError(RuntimeException(errorMessage ?: "Geocoder error"))
        }
      )
    } else {
      legacyExecutor.execute {
        try {
          @Suppress("DEPRECATION")
          val list = geocoder.getFromLocationName(
            query, maxResults,
            llLat, llLng, urLat, urLng
          ) ?: emptyList()
          cb.onResults(list)
        } catch (e: Exception) {
          cb.onError(e)
        }
      }
    }
  }

  private fun serializeAddress(a: Address, category: String?): WritableMap {
    val m = Arguments.createMap()
    a.featureName?.takeIf { it.isNotBlank() }?.let { m.putString("name", it) }
    a.phone?.takeIf { it.isNotBlank() }?.let { m.putString("phoneNumber", it) }
    a.url?.takeIf { it.isNotBlank() }?.let { m.putString("url", it) }
    if (category != null) m.putString("category", category)

    val coord = Arguments.createMap()
    coord.putDouble("latitude", if (a.hasLatitude()) a.latitude else 0.0)
    coord.putDouble("longitude", if (a.hasLongitude()) a.longitude else 0.0)
    m.putMap("coordinate", coord)

    a.thoroughfare?.takeIf { it.isNotBlank() }?.let { m.putString("thoroughfare", it) }
    a.subThoroughfare?.takeIf { it.isNotBlank() }?.let { m.putString("subThoroughfare", it) }
    a.locality?.takeIf { it.isNotBlank() }?.let { m.putString("locality", it) }
    a.subLocality?.takeIf { it.isNotBlank() }?.let { m.putString("subLocality", it) }
    a.adminArea?.takeIf { it.isNotBlank() }?.let { m.putString("administrativeArea", it) }
    a.postalCode?.takeIf { it.isNotBlank() }?.let { m.putString("postalCode", it) }
    a.countryCode?.takeIf { it.isNotBlank() }?.let { m.putString("isoCountryCode", it) }
    a.countryName?.takeIf { it.isNotBlank() }?.let { m.putString("country", it) }
    return m
  }

  /**
   * `Address` carries no "is this a POI?" bit; we approximate by treating
   * a result as address-only when its `featureName` is missing or matches
   * the street number / thoroughfare exactly.
   */
  private fun isAddressOnly(a: Address): Boolean {
    val name = a.featureName?.trim().orEmpty()
    if (name.isEmpty()) return true
    val sub = a.subThoroughfare?.trim().orEmpty()
    val thoroughfare = a.thoroughfare?.trim().orEmpty()
    if (name.equals(sub, ignoreCase = true)) return true
    if (name.equals(thoroughfare, ignoreCase = true)) return true
    if (sub.isNotEmpty() && name.equals("$sub $thoroughfare".trim(), ignoreCase = true)) return true
    return false
  }

  private fun dedupeKey(a: Address): String {
    val name = (a.featureName ?: "").trim().lowercase()
    val lat = if (a.hasLatitude()) "%.5f".format(a.latitude) else "-"
    val lng = if (a.hasLongitude()) "%.5f".format(a.longitude) else "-"
    return "$name|$lat|$lng"
  }

  private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val out = FloatArray(1)
    android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, out)
    return out[0].toDouble()
  }

  private fun ReadableArray.toStringList(): List<String> {
    val out = ArrayList<String>(size())
    for (i in 0 until size()) {
      val s = getString(i) ?: continue
      out.add(s)
    }
    return out
  }

  companion object {
    const val NAME = "NativeNearbyPlaces"
    private const val TAG = "RNNP"

    private const val E_UNAVAILABLE = "E_NEARBY_PLACES_UNAVAILABLE"
    private const val E_SEARCH = "E_NEARBY_PLACES_SEARCH"
    private const val E_NEARBY = "E_NEARBY_PLACES_NEARBY"
    private const val GEOCODER_UNAVAILABLE_MSG =
      "Geocoder service is not available on this device " +
        "(the system geocoder backend is missing — common on AOSP-only builds)."

    private const val MAX_RESULTS_SEARCH = 30
    private const val MAX_RESULTS_PER_CATEGORY = 12

    /**
     * Best-effort mapping from MKPOICategory-style names to English
     * keywords that Android's Geocoder responds to. Geocoder has no POI
     * categories of its own — these are search strings, not enums. Words
     * chosen for hit-rate over precision (e.g. `foodMarket → "grocery"`
     * because "food market" returns too few results on device geocoders).
     */
    private val CATEGORY_KEYWORDS: Map<String, String> = linkedMapOf(
      "restaurant" to "restaurant",
      "cafe" to "cafe",
      "bakery" to "bakery",
      "brewery" to "brewery",
      "winery" to "winery",
      "foodMarket" to "grocery",
      "nightlife" to "bar",
    )

    /**
     * Mapping from our `POICategory` names to Google Places Table A types
     * (https://developers.google.com/maps/documentation/places/web-service/place-types).
     * Several Apple categories don't have a 1:1 Places equivalent
     * (`brewery` and `winery` aren't separate types), so we use the
     * closest umbrella. `nightlife` deliberately expands to two types so
     * the picker surfaces both wine bars and dance clubs.
     */
    private val PLACES_TYPE_MAP: Map<String, List<String>> = linkedMapOf(
      "restaurant" to listOf("restaurant"),
      "cafe" to listOf("cafe"),
      "bakery" to listOf("bakery"),
      "brewery" to listOf("bar"),
      "winery" to listOf("liquor_store"),
      "foodMarket" to listOf("supermarket"),
      "nightlife" to listOf("bar", "night_club"),
    )
  }
}
