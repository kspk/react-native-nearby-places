package com.reactnativenearbyplaces

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client for Google's Places API (New) — Nearby Search endpoint.
 *
 * We hit `places.googleapis.com/v1/places:searchNearby` directly rather
 * than pulling the Places SDK for Android, which would add a few hundred
 * KB to the consumer's APK for one endpoint. OkHttp is already on the
 * React Native classpath, so this needs no new Gradle deps.
 *
 * **Field mask is intentionally minimal.** Per Google's pricing model
 * (effective March 2025), each result is billed under the highest tier
 * any returned field belongs to. The fields we request below all sit at
 * Pro tier ($32/1k as of this writing). Adding `phoneNumber`, `websiteUri`,
 * or contact info bumps to Enterprise ($40/1k); adding `rating`,
 * `priceLevel`, or `regularOpeningHours` bumps to Enterprise Atmosphere
 * ($50/1k). Keep this mask in lockstep with what `PlacePickerSheet`
 * actually consumes — see https://developers.google.com/maps/billing-and-pricing/pricing.
 *
 * **Caching.** Per Maps Platform ToS §3.2.3(b), only `places.id` (place_id)
 * may be persisted indefinitely. `displayName`, `location`, and
 * `shortFormattedAddress` cannot be cached beyond session scope. We never
 * cache anything here — the response goes straight back to JS for one-shot
 * display. Consumers using Pattern B (user-confirmed save, device GPS) stay
 * ToS-clean without further work.
 */
internal class PlacesApiClient(private val apiKey: String) {

  /** Single place + the fields we requested (kept tight to control billing). */
  data class PlaceResult(
    val id: String,
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
  )

  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build()
  }

  /**
   * Blocking HTTP call. **Must be invoked off the main thread** — caller
   * is responsible for executor scheduling.
   *
   * @throws IOException on network failure or non-2xx HTTP status. The
   * caller decides whether to translate that into a rejected JS promise or
   * fall back to another provider.
   */
  fun searchNearby(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    includedTypes: List<String>,
  ): List<PlaceResult> {
    val safeRadius = radiusMeters.coerceIn(1.0, 50_000.0)
    val body = JSONObject().apply {
      put("includedTypes", JSONArray(includedTypes))
      put("maxResultCount", MAX_RESULTS)
      put(
        "locationRestriction",
        JSONObject().put(
          "circle",
          JSONObject()
            .put(
              "center",
              JSONObject()
                .put("latitude", latitude)
                .put("longitude", longitude),
            )
            .put("radius", safeRadius),
        ),
      )
    }.toString()

    val request = Request.Builder()
      .url(URL)
      .post(body.toRequestBody(JSON_MEDIA))
      .addHeader("X-Goog-Api-Key", apiKey)
      .addHeader("X-Goog-FieldMask", FIELD_MASK)
      .build()

    Log.d(TAG, "POST searchNearby types=$includedTypes radius=$safeRadius")
    client.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        // Truncate the body so we don't dump a huge error blob into the
        // user's JS console. Status code + opening 200 chars is plenty
        // for diagnosis.
        Log.w(TAG, "HTTP ${response.code}: ${text.take(200)}")
        throw IOException("Places API HTTP ${response.code}: ${text.take(200)}")
      }
      return parseResponse(text)
    }
  }

  private fun parseResponse(json: String): List<PlaceResult> {
    val root = try {
      JSONObject(json)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse Places response: ${e.message}")
      return emptyList()
    }
    val places = root.optJSONArray("places") ?: return emptyList()
    val out = ArrayList<PlaceResult>(places.length())
    for (i in 0 until places.length()) {
      val p = places.optJSONObject(i) ?: continue
      val id = p.optString("id").takeIf { it.isNotEmpty() } ?: continue
      val name = p.optJSONObject("displayName")
        ?.optString("text")
        ?.takeIf { it.isNotEmpty() }
      val loc = p.optJSONObject("location") ?: continue
      val lat = loc.optDouble("latitude", Double.NaN)
      val lng = loc.optDouble("longitude", Double.NaN)
      if (lat.isNaN() || lng.isNaN()) continue
      val address = p.optString("shortFormattedAddress").takeIf { it.isNotEmpty() }
      out.add(
        PlaceResult(
          id = id,
          name = name,
          latitude = lat,
          longitude = lng,
          address = address,
        ),
      )
    }
    return out
  }

  companion object {
    private const val TAG = "RNNP-Places"
    private const val URL = "https://places.googleapis.com/v1/places:searchNearby"

    /**
     * Pro-tier field mask. **Bumping this can bump your billing tier** —
     * see https://developers.google.com/maps/billing-and-pricing/pricing.
     */
    private const val FIELD_MASK =
      "places.id,places.displayName,places.location,places.shortFormattedAddress"

    /** Places API caps at 20 results per request. */
    private const val MAX_RESULTS = 20

    private val JSON_MEDIA = "application/json".toMediaType()
  }
}
