package com.waveq.app.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.waveq.app.data.local.WaveQDatabase

class IncidentSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dao = WaveQDatabase.getDatabase(applicationContext).incidentDao()
        val pendingIncidents = dao.getPendingSyncIncidents()

        if (pendingIncidents.isEmpty()) {
            return Result.success()
        }

        return try {
            for (incident in pendingIncidents) {
                Log.d("WaveQSync", "Syncing incident ${incident.id} to remote server...")
                dao.updateIncident(incident.copy(isSynced = true))
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("WaveQSync", "Failed syncing incidents, will retry when network returns", e)
            Result.retry()
        }
    }
}