package com.waveq.app.data

import android.content.Context
import android.util.Log
import com.waveq.app.auth.SessionManager
import com.waveq.app.auth.UserRole
import com.waveq.app.data.local.IncidentEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "IncidentReporter"

/** Outcome of a filed report, surfaced to whatever UI happens to be on screen when it completes. */
data class ReportSubmission(val referenceId: String, val peersReached: Int)

/**
 * Outcome of an operator confirming or dismissing a report.
 *
 * Exists because the confirmation relay was fire-and-forget with a `Log.i` -
 * the operator tapped Confirm, the row changed state, and whether the
 * accompanying alert reached anybody was invisible. A confirmation that went to
 * zero peers looks identical to one that reached the whole district.
 */
data class ModerationResult(
    val referenceId: String,
    val confirmed: Boolean,
    val peersReached: Int,
    val alertSent: Boolean,
)

/**
 * Owns the "file a report" pipeline: store locally, relay over the mesh, record
 * that it was relayed.
 *
 * Process-scoped on purpose. The pipeline previously ran in the report screen's
 * `rememberCoroutineScope()`, which is cancelled the instant that destination
 * leaves composition - so pressing back (or opening the drawer) between tapping
 * Submit and the write completing could leave the report unsaved, saved but
 * never relayed, or relayed but never marked as such. The sheet closes
 * synchronously either way, so the user saw no error and reasonably assumed it
 * had worked. A report must survive the user navigating away from the screen
 * they filed it on.
 *
 * The result is published as observable state rather than written into a
 * composable-local `var`, so the confirmation still appears if the user returns
 * to the screen while the write is in flight.
 */
object IncidentReporter {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "filing an incident report failed", throwable)
            },
    )

    private val _lastSubmission = MutableStateFlow<ReportSubmission?>(null)
    val lastSubmission: StateFlow<ReportSubmission?> = _lastSubmission.asStateFlow()

    private val _lastModeration = MutableStateFlow<ModerationResult?>(null)
    val lastModeration: StateFlow<ModerationResult?> = _lastModeration.asStateFlow()

    fun consumeModeration() {
        _lastModeration.value = null
    }

    /**
     * Stores a report and hands it to the mesh.
     *
     * [relay] is invoked after the local write has committed - storing first is
     * the whole point, since filing must not depend on having a peer in range.
     * It returns the number of peers the payload was dispatched to.
     */
    fun submit(
        context: Context,
        type: String,
        severity: String,
        location: String,
        description: String,
        latitude: Double?,
        longitude: Double?,
        relay: (IncidentEntity) -> Int,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val repository = IncidentRepository(appContext)
            val saved = repository.fileReport(
                type = type,
                severity = severity,
                location = location,
                description = description,
                latitude = latitude,
                longitude = longitude,
            )
            val peersReached = relay(saved)
            if (peersReached > 0) repository.markBroadcast(saved.id)
            _lastSubmission.value = ReportSubmission(saved.id, peersReached)
        }
    }

    /** Called once the confirmation has been shown and dismissed. */
    fun consumeSubmission() {
        _lastSubmission.value = null
    }

    // -- Operator moderation ----------------------------------------------

    /**
     * Confirms a citizen report.
     *
     * The role is checked HERE, immediately before the write, not only at the
     * nav graph - same reasoning as MeshManager.sendMessage's FLOOD_ALERT gate.
     * A hidden button is not a gate; the only check that counts is the one the
     * write itself cannot bypass.
     *
     * [relay] is invoked after the local write commits, to announce the
     * confirmation to the mesh. It returns the peer count, which is logged
     * rather than surfaced - the operator's action succeeded locally whether or
     * not anyone was in range to hear about it.
     */
    fun verify(context: Context, incidentId: String, relay: (IncidentEntity) -> Int) {
        moderate(context, incidentId, confirm = true, relay = relay)
    }

    /** Dismisses a report. Marks it rejected; never deletes it. */
    fun dismiss(context: Context, incidentId: String) {
        moderate(context, incidentId, confirm = false, relay = { 0 })
    }

    private fun moderate(
        context: Context,
        incidentId: String,
        confirm: Boolean,
        relay: (IncidentEntity) -> Int,
    ) {
        val session = SessionManager.session.value
        val role = session?.role
        if (role != UserRole.OPERATOR && role != UserRole.ADMIN) {
            Log.w(TAG, "moderation blocked - role=$role is not OPERATOR/ADMIN")
            return
        }
        val reviewer = session.displayName
        val appContext = context.applicationContext
        scope.launch {
            val repository = IncidentRepository(appContext)
            if (confirm) {
                repository.markVerified(incidentId, reviewer)
                val updated = repository.byId(incidentId)
                if (updated == null) {
                    Log.w(TAG, "confirmed $incidentId but it vanished before the announcement")
                    return@launch
                }
                // relay returns the transport's real dispatch count: 0 means the
                // mesh is off, or nothing was in range. Either way the operator
                // is told, rather than the failure living in logcat.
                val peers = relay(updated)
                Log.i(TAG, "confirmed $incidentId, announced to $peers peer(s)")
                _lastModeration.value = ModerationResult(
                    referenceId = incidentId,
                    confirmed = true,
                    peersReached = peers,
                    alertSent = peers > 0,
                )
            } else {
                repository.markRejected(incidentId, reviewer)
                _lastModeration.value = ModerationResult(
                    referenceId = incidentId,
                    confirmed = false,
                    peersReached = 0,
                    alertSent = false,
                )
            }
        }
    }
}
