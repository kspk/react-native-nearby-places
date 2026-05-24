package com.reactnativenearbyplaces

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import java.util.concurrent.Executors

/**
 * Android implementation. Backed exclusively by Google Places API (New)
 * when the consumer has provisioned an API key. If no key is configured,
 * both methods short-circuit predictably:
 *
 *  - `searchNearby()` resolves with an empty array so the UI can render a
 *    clean "no results" state.
 *  - `search()` rejects with `E_NEARBY_PLACES_NOT_SUPPORTED` — there's no
 *    text-query backend wired in this version (Places Text Search is a
 *    separate endpoint planned for a future release).
 *
 * **No Geocoder fallback.** Earlier versions (0.2.x / 0.3.0) backed
 * `searchNearby` on `android.location.Geocoder` when no key was present.
 * Geocoder is an address service, not a POI index — it returned ~0 useful
 * results for category browsing. The fallback was removed in 0.4 to keep
 * behavior predictable: no key = empty, no surprises.
 */
@ReactModule(name = ReactNativeNearbyPlacesModule.NAME)
class ReactNativeNearbyPlacesModule(reactContext: ReactApplicationContext) :
  NativeNearbyPlacesSpec(reactContext) {

  /**
   * Off-main-thread executor for the blocking OkHttp call inside
   * `PlacesApiClient`. Single worker — Places calls are user-initiated,
   * not high-frequency.
   */
  private val networkExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "RNNP-network").apply { isDaemon = true }
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
    promise.reject(
      E_NOT_SUPPORTED,
      "search() is not supported on Android in this version. " +
        "Use searchNearby() for category-based discovery, or wait for " +
        "Places Text Search support in a future release.",
    )
  }

  override fun searchNearby(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    categories: ReadableArray,
    promise: Promise
  ) {
    Log.d(TAG, "searchNearby: lat=$latitude lng=$longitude r=$radiusMeters cats=${categories.toStringList()}")

    val apiKey = getGooglePlacesApiKey()
    if (apiKey == null) {
      // No key configured → empty result set. Predictable shape for
      // callers; UI renders a "no results" state cleanly.
      Log.d(TAG, "No com.google.android.geo.API_KEY in manifest; returning empty.")
      promise.resolve(Arguments.createArray())
      return
    }

    val requested = categories.toStringList().ifEmpty { PLACES_TYPE_MAP.keys.toList() }
    val placesTypes = requested
      .flatMap { name -> PLACES_TYPE_MAP[name] ?: emptyList() }
      .distinct()
    if (placesTypes.isEmpty()) {
      promise.resolve(Arguments.createArray())
      return
    }

    // Map Places-type → our category for best-effort tagging on the JS
    // side. Multiple categories may map to the same Places type
    // (`brewery` + `nightlife` → `bar`); first-listed wins.
    val typeToCategory: Map<String, String> = PLACES_TYPE_MAP.entries
      .flatMap { (cat, types) -> types.map { it to cat } }
      .groupBy({ it.first }, { it.second })
      .mapValues { it.value.first() }

    networkExecutor.execute {
      try {
        val client = PlacesApiClient(apiKey)
        val results = client.searchNearby(latitude, longitude, radiusMeters, placesTypes)
        Log.d(TAG, "Places API returned ${results.size} results")
        val out = Arguments.createArray()
        for (r in results) {
          // We don't know which Places type each result actually matched —
          // the response with this field mask doesn't carry that info — so
          // we tag with the first category that asked for any type.
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

  // ── helpers ───────────────────────────────────────────────────────────

  /**
   * Reads `com.google.android.geo.API_KEY` from the host app's manifest
   * `<meta-data>`. Returns `null` if absent or blank — we treat absence
   * as "no key configured", not as an error. This is the same meta-data
   * name the Google Maps SDK reads from, so a consumer that already uses
   * Maps doesn't need a separate key.
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
    // expectation — surface it via `thoroughfare` so JS-side consumers
    // (e.g. ForkLog's PlacePickerSheet) render it without branching.
    r.address?.let { m.putString("thoroughfare", it) }
    return m
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

    private const val E_NOT_SUPPORTED = "E_NEARBY_PLACES_NOT_SUPPORTED"
    private const val E_NEARBY = "E_NEARBY_PLACES_NEARBY"

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
