package com.waveq.app.data

import android.content.Context
import com.waveq.app.data.local.IncidentEntity
import com.waveq.app.data.local.WaveQDatabase
import com.waveq.app.data.sync.IncidentSyncWorker
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Single entry point for filing and reading incident reports.
 *
 * A report is written to the local database first and queued for sync, so
 * filing one never depends on having a network at that moment - which is the
 * whole point in a flood.
 */
class IncidentRepository(private val context: Context) {

    private val dao = WaveQDatabase.getDatabase(context).incidentDao()

    fun observeAll(): Flow<List<IncidentEntity>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    suspend fun byId(id: String): IncidentEntity? = dao.byId(id)

    suspend fun markVerified(id: String, by: String, at: Long = System.currentTimeMillis()) =
        dao.markVerified(id, by, at)

    suspend fun markRejected(id: String, by: String, at: Long = System.currentTimeMillis()) =
        dao.markRejected(id, by, at)

    suspend fun recent(limit: Int = 20): List<IncidentEntity> = dao.recent(limit)

    /**
     * Stores a report locally and queues it for upload. Returns the stored row
     * so the caller can report honestly on what actually happened rather than
     * assuming delivery.
     */
    suspend fun fileReport(
        type: String,
        severity: String,
        location: String,
        description: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ): IncidentEntity {
        val entity = IncidentEntity(
            id = "INC-${UUID.randomUUID().toString().take(8).uppercase()}",
            type = type,
            location = location,
            description = description,
            severity = severity,
            reportedAtMillis = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
        )
        dao.insert(entity)
        IncidentSyncWorker.enqueue(context)
        return entity
    }

    suspend fun markBroadcast(id: String) = dao.markBroadcast(id)

    /**
     * Stores a report that arrived over the mesh from another device.
     *
     * Marked broadcast (it is already on the mesh - that is how it got here) and
     * NOT queued for sync: this device is not the filer and must not upload
     * someone else's report as its own. It arrives unverified like any other,
     * and becomes visible in the public view only once an operator's
     * confirmation follows.
     */
    suspend fun storeRelayedReport(
        id: String,
        type: String,
        severity: String,
        location: String,
        description: String,
        reportedAtMillis: Long,
        latitude: Double? = null,
        longitude: Double? = null,
    ) {
        dao.insert(
            IncidentEntity(
                id = id,
                type = type,
                location = location,
                description = description,
                severity = severity,
                reportedAtMillis = reportedAtMillis,
                latitude = latitude,
                longitude = longitude,
                isBroadcast = true,
            ),
        )
    }

    /** Backfills coordinates for a report that arrived without them. */
    suspend fun updateCoordinates(id: String, latitude: Double?, longitude: Double?) =
        dao.updateCoordinates(id, latitude, longitude)
}
