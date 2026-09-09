package com.waveq.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.waveq.app.data.local.IncidentEntity
import com.waveq.app.location.LocationController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * Read side of the incident store, and the entry point the report flow submits
 * through.
 *
 * Reports were previously written to Room and never read back by anything: the
 * operator dashboard rendered a hardcoded sample list and Home printed a
 * hardcoded count, so a filed report appeared nowhere in the app. These flows
 * are the missing half.
 *
 * Scoped to the Activity (obtained once in AppRoot), so navigating between
 * destinations does not tear the collectors down. Submission itself deliberately
 * does NOT run on [viewModelScope] - see [IncidentReporter].
 */
class IncidentViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IncidentRepository(application)

    val incidents: StateFlow<List<IncidentEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    val incidentCount: StateFlow<Int> = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), 0)

    /** How many reports are waiting for an operator decision. */
    val pendingCount: StateFlow<Int> = repository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), 0)

    /**
     * Confirms a report and announces it to the mesh.
     *
     * The role check lives in [IncidentReporter.verify], immediately before the
     * write - the nav-level gate keeps the screen off a citizen's device, but it
     * is not what makes the write safe.
     */
    fun verifyReport(incidentId: String, relay: (IncidentEntity) -> Int) {
        IncidentReporter.verify(getApplication(), incidentId, relay)
    }

    fun dismissReport(incidentId: String) {
        IncidentReporter.dismiss(getApplication(), incidentId)
    }

    /** Result of the most recent filed report, or null once it has been acknowledged. */
    val lastSubmission: StateFlow<ReportSubmission?> = IncidentReporter.lastSubmission

    /**
     * Files a report. Runs on [IncidentReporter]'s process-scoped coroutine, not
     * on [viewModelScope] - the write must survive this ViewModel being cleared,
     * not just the screen being recomposed.
     *
     * Coordinates come from the real GPS fix, never the viewing override: a
     * report is an observation about where the reporter actually is. Null when
     * there is no fix, which is honest rather than a sentinel.
     */
    fun submitReport(
        type: String,
        severity: String,
        location: String,
        description: String,
        relay: (IncidentEntity) -> Int,
    ) {
        val fix = LocationController.deviceLocation.value
        IncidentReporter.submit(
            context = getApplication(),
            type = type,
            severity = severity,
            location = location,
            description = description,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            relay = relay,
        )
    }

    fun consumeSubmission() = IncidentReporter.consumeSubmission()

    /** Result of the most recent confirm/dismiss, so the operator sees what actually happened. */
    val lastModeration: StateFlow<ModerationResult?> = IncidentReporter.lastModeration

    fun consumeModeration() = IncidentReporter.consumeModeration()
}
