package com.waveq.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A citizen- or operator-filed incident report, stored locally first so a
 * report survives having no connectivity at the moment it is filed.
 *
 * [isSynced] drives IncidentSyncWorker; [isBroadcast] tracks whether the
 * report has also gone out over the Bluetooth mesh, which is a separate
 * delivery path from the (not yet existing) backend.
 */
@Entity(
    tableName = "incidents",
    indices = [Index("isSynced"), Index("reportedAtMillis")],
)
data class IncidentEntity(
    @PrimaryKey val id: String,
    val type: String,
    val location: String,
    val description: String,
    val severity: String,
    val reportedAtMillis: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val photoUri: String? = null,
    val audioPath: String? = null,
    val verified: Boolean = false,
    /**
     * Who reviewed this report and when. Set for BOTH outcomes - a confirmation
     * and a dismissal are both operator decisions and both need attribution.
     * Null while the report is still pending.
     */
    val verifiedBy: String? = null,
    val verifiedAtMillis: Long? = null,
    /**
     * Dismissed by an operator. Dismissal is not deletion: the report stays in
     * the record (so the same thing is not re-triaged forever, and so a wrong
     * dismissal is visible) but stays out of the public view.
     */
    val isRejected: Boolean = false,
    val isSynced: Boolean = false,
    val isBroadcast: Boolean = false,
) {
    /** Pending review: neither confirmed nor dismissed. */
    val isPending: Boolean get() = !verified && !isRejected
}
