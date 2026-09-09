package com.waveq.app.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.waveq.app.data.local.WaveQDatabase

/**
 * Flushes locally-stored incidents once the network returns.
 *
 * TODO: there is no backend yet. The upload step below is a no-op that simply
 * flips isSynced, so the queue drains and the retry/backoff path can be
 * exercised - it does NOT mean a report reached any server. Replace the body
 * of the loop with a real API call before this ships, and do not surface
 * "synced" to the user as "received by authorities" until then.
 */
class IncidentSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dao = WaveQDatabase.getDatabase(applicationContext).incidentDao()
        val pending = dao.pendingSync()
        if (pending.isEmpty()) return Result.success()

        return try {
            for (incident in pending) {
                Log.d(TAG, "Would upload incident ${incident.id} (no backend wired yet)")
                dao.markSynced(incident.id)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Incident sync failed, will retry when the network returns", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WaveQSync"
        private const val WORK_NAME = "incident-sync"

        /** Queues a sync attempt that waits for connectivity. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<IncidentSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
