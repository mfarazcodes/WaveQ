package com.waveq.app.data

import android.content.Context
import android.util.Log
import com.waveq.app.mesh.MeshManager
import com.waveq.app.mesh.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "IncidentMeshSync"

/**
 * The reference-id marker carried in relayed report and verification text.
 *
 * A citizen report goes out as `[Report INC-XXXXXXXX] …` (TEXT); an operator's
 * confirmation of that same report goes out as `[Verified INC-XXXXXXXX] …`
 * (FLOOD_ALERT). Matching on the id is what lets a receiving device mark the
 * report it already holds as confirmed instead of storing a second copy of the
 * same incident.
 */
private val REFERENCE_PATTERN = Regex("""\[(?:Report|Verified) (INC-[A-Z0-9]+)]""")

fun extractIncidentReference(text: String?): String? =
    text?.let { REFERENCE_PATTERN.find(it)?.groupValues?.getOrNull(1) }

/** Builds the body of a verification announcement, carrying the original reference id. */
fun verificationAnnouncement(referenceId: String, type: String, location: String): String =
    "[Verified $referenceId] $type confirmed by an operator at $location"

/**
 * Legacy fallback only.
 *
 * Reports now travel as structured JSON in `MeshPayload.incidentJson`; this
 * regex reads the prose form that builds before that field sent. It cannot
 * recover coordinates - the sentence never contained them - so a report ingested
 * this way has a location string and no plottable position, which is exactly why
 * the structured field exists.
 */
private val REPORT_BODY_PATTERN =
    Regex("""\[Report (INC-[A-Z0-9]+)]\s*(.+?)\s+-\s+(\w+)\s+severity at\s+([^.]+)\.?\s*(.*)""")

/**
 * Applies operator confirmations that arrive over the mesh to the local store.
 *
 * Process-scoped, started from MeshSession, so a confirmation broadcast while
 * this device's UI is closed is still applied - the same reason alert dispatch
 * lives outside the ViewModel.
 *
 * Deliberately only *upgrades* a report this device already holds. It never
 * creates one: a FLOOD_ALERT is an assertion that some report was confirmed, and
 * a device that never saw the original has no incident body to attach it to.
 * Fabricating one from the alert text would invent a report nobody filed.
 */
object IncidentMeshSync {

    @Volatile private var started = false

    /**
     * Structured payload first, prose second.
     *
     * The fallback exists for devices on an older build; anything it produces
     * has no coordinates and therefore cannot be plotted, which the map reports
     * rather than hiding.
     */
    fun incidentFrom(incidentJson: String?, text: String?, timestampMs: Long): IncidentWire? {
        IncidentWire.fromJson(incidentJson)?.let { return it }
        val match = REPORT_BODY_PATTERN.find(text.orEmpty()) ?: return null
        val (referenceId, type, severityWord, location, description) = match.destructured
        return IncidentWire(
            id = referenceId,
            type = type.trim(),
            severity = severityWord.trim().uppercase(),
            location = location.trim(),
            description = description.trim(),
            latitude = null,
            longitude = null,
            reportedAtMillis = timestampMs,
        )
    }

    @Synchronized
    fun start(context: Context, meshManager: MeshManager, scope: CoroutineScope) {
        if (started) return
        started = true
        val appContext = context.applicationContext

        // Citizen reports arriving from other devices. Without this a device
        // holds no incidents at all unless its own user filed one, so the public
        // crisis view and its map were permanently empty for everyone else -
        // and a later operator confirmation had no local row to upgrade.
        scope.launch {
            meshManager.incomingMessages.collect { message ->
                if (message.type != MessageType.TEXT) return@collect
                if (message.isMine) return@collect
                val incoming = incidentFrom(message.incidentJson, message.text, message.timestamp)
                    ?: return@collect

                runCatching {
                    val repository = IncidentRepository(appContext)
                    if (repository.byId(incoming.id) != null) return@runCatching
                    repository.storeRelayedReport(
                        id = incoming.id,
                        type = incoming.type,
                        severity = incoming.severity,
                        location = incoming.location,
                        description = incoming.description,
                        reportedAtMillis = incoming.reportedAtMillis,
                        latitude = incoming.latitude,
                        longitude = incoming.longitude,
                    )
                    Log.i(
                        TAG,
                        "stored relayed report ${incoming.id}" +
                            if (incoming.latitude == null) " (no coordinates - not mappable)" else "",
                    )
                }.onFailure { Log.w(TAG, "failed to store relayed report", it) }
            }
        }

        scope.launch {
            meshManager.incomingMessages.collect { message ->
                if (message.type != MessageType.FLOOD_ALERT) return@collect
                if (message.isMine) return@collect
                val structured = IncidentWire.fromJson(message.incidentJson)
                val referenceId = structured?.id
                    ?: extractIncidentReference(message.text)
                    ?: return@collect

                runCatching {
                    val repository = IncidentRepository(appContext)
                    val existing = repository.byId(referenceId)
                    if (existing == null) {
                        // The original report never reached this device - a
                        // confirmation alone carries no incident body, so there
                        // is nothing to create from it.
                        Log.d(TAG, "confirmation for $referenceId, which this device never received")
                        return@runCatching
                    }
                    if (existing.verified) return@runCatching
                    // A confirmation may carry coordinates this device never had
                    // - the original report could have reached it from a build
                    // that sent no structured payload.
                    if (existing.latitude == null && structured?.latitude != null) {
                        repository.updateCoordinates(
                            id = referenceId,
                            latitude = structured.latitude,
                            longitude = structured.longitude,
                        )
                    }
                    repository.markVerified(
                        id = referenceId,
                        by = structured?.verifiedBy ?: message.senderName,
                        at = message.timestamp,
                    )
                    Log.i(TAG, "marked $referenceId verified from mesh confirmation")
                }.onFailure { Log.w(TAG, "failed to apply mesh confirmation", it) }
            }
        }
    }
}
