package com.waveq.app.data

import com.waveq.app.data.local.IncidentEntity
import org.json.JSONObject

/**
 * An incident as it travels over the mesh.
 *
 * Replaces re-parsing the human-readable body with a regex. That parser was
 * fragile by construction - it read back prose this app had written itself - and
 * it caused two concrete bugs:
 *
 *  - it could not recover coordinates at all, because the body never contained
 *    them, so every report ingested from a peer had a location *string* and no
 *    plottable position, and vanished from the crisis map; and
 *  - the whole body, reference tag and all, ended up wherever a place name was
 *    expected, so the takeover's "Location" row read
 *    `[Verified INC-A1B2C3D4] Flood confirmed by an operator at Sector 12`.
 *
 * Carried as an extra optional field on MeshPayload, so a device running an
 * older build simply ignores it and falls back to the prose - the wire format
 * stays backward compatible in both directions.
 */
data class IncidentWire(
    val id: String,
    val type: String,
    /** A [com.waveq.app.ui.components.Severity] name, not a display label. */
    val severity: String,
    val location: String,
    val description: String,
    val latitude: Double?,
    val longitude: Double?,
    val reportedAtMillis: Long,
    val verifiedBy: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("severity", severity)
        put("location", location)
        put("description", description)
        put("latitude", latitude ?: JSONObject.NULL)
        put("longitude", longitude ?: JSONObject.NULL)
        put("reportedAt", reportedAtMillis)
        put("verifiedBy", verifiedBy ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromEntity(entity: IncidentEntity, verifiedBy: String? = null) = IncidentWire(
            id = entity.id,
            type = entity.type,
            severity = entity.severity,
            location = entity.location,
            description = entity.description,
            latitude = entity.latitude,
            longitude = entity.longitude,
            reportedAtMillis = entity.reportedAtMillis,
            verifiedBy = verifiedBy ?: entity.verifiedBy,
        )

        /** Returns null for anything malformed - this parses bytes off an unencrypted channel. */
        fun fromJson(json: String?): IncidentWire? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
                IncidentWire(
                    id = id,
                    type = obj.optString("type").takeIf { it.isNotBlank() } ?: "Incident",
                    severity = obj.optString("severity").takeIf { it.isNotBlank() } ?: "MEDIUM",
                    location = obj.optString("location"),
                    description = obj.optString("description"),
                    latitude = if (obj.isNull("latitude")) null else obj.optDouble("latitude"),
                    longitude = if (obj.isNull("longitude")) null else obj.optDouble("longitude"),
                    reportedAtMillis = obj.optLong("reportedAt", System.currentTimeMillis()),
                    verifiedBy = if (obj.isNull("verifiedBy")) null else obj.optString("verifiedBy"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
