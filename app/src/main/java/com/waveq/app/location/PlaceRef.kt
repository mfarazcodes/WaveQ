package com.waveq.app.location

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named place with coordinates.
 *
 * Used for three different things that must not be confused with each other:
 * a search result, a place the user pinned, and the reverse-geocoded name of
 * where the device actually is. They are the same shape, so they share a type -
 * but which *role* a given instance is playing is always carried by the field
 * it is stored in ([LocationController.devicePlace] vs
 * [LocationController.viewingOverride]), never inferred from the value itself.
 */
data class PlaceRef(
    val name: String,
    /** State - Open-Meteo's admin1. Indian place names repeat heavily, so this is rarely optional in practice. */
    val admin1: String?,
    /** District - Open-Meteo's admin2. */
    val admin2: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    /** User-assigned pin label ("Home", "Parents", "Village"); null for an unpinned place. */
    val label: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    /**
     * "Dehradun, Uttarakhand" - district then state.
     *
     * This is not decoration. There are Rampurs in at least six states and
     * dozens of Rampurs within them; a bare place name in an Indian picker is
     * genuinely ambiguous, so the district and state carry the disambiguation.
     */
    fun subtitle(): String = listOfNotNull(admin2, admin1, country?.takeIf { it != "India" })
        .distinct()
        .joinToString(", ")
        .ifBlank { "%.4f, %.4f".format(latitude, longitude) }

    /** Everything on one line, for a banner or a notification. */
    fun fullLabel(): String = listOfNotNull(name, admin2, admin1).distinct().joinToString(", ")

    /**
     * Identity for dedup in the recents and saved lists. Three decimal places is
     * ~110 m: fine enough that two genuinely different towns never collide,
     * coarse enough that the same town found through two different searches is
     * recognised as one entry.
     */
    fun coordinateKey(): String = "%.3f,%.3f".format(latitude, longitude)

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("admin1", admin1 ?: JSONObject.NULL)
        put("admin2", admin2 ?: JSONObject.NULL)
        put("country", country ?: JSONObject.NULL)
        put("latitude", latitude)
        put("longitude", longitude)
        put("label", label ?: JSONObject.NULL)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): PlaceRef? {
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
            val latitude = obj.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() } ?: return null
            val longitude = obj.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() } ?: return null
            return PlaceRef(
                name = name,
                admin1 = obj.optStringOrNull("admin1"),
                admin2 = obj.optStringOrNull("admin2"),
                country = obj.optStringOrNull("country"),
                latitude = latitude,
                longitude = longitude,
                label = obj.optStringOrNull("label"),
                addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
            )
        }

        fun listToJson(places: List<PlaceRef>): String {
            val array = JSONArray()
            places.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(json: String?): List<PlaceRef> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { fromJson(it) }
                }
            }.getOrElse { emptyList() }
        }
    }
}

/** org.json turns a missing key into the empty string, which is not the same as absent. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
