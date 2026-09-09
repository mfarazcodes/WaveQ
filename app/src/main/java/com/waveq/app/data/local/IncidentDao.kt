package com.waveq.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IncidentDao {
    /**
     * Pending reports first, then newest first within each group - an operator
     * opens this screen to triage, so what needs a decision belongs at the top.
     */
    @Query(
        """
        SELECT * FROM incidents
        ORDER BY (verified = 0 AND isRejected = 0) DESC, reportedAtMillis DESC
        """,
    )
    fun observeAll(): Flow<List<IncidentEntity>>

    @Query("SELECT COUNT(*) FROM incidents WHERE verified = 0 AND isRejected = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM incidents WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): IncidentEntity?

    @Query("SELECT * FROM incidents ORDER BY reportedAtMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<IncidentEntity>

    @Query("SELECT COUNT(*) FROM incidents")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM incidents WHERE isSynced = 0")
    suspend fun pendingSync(): List<IncidentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(incident: IncidentEntity)

    @Update
    suspend fun update(incident: IncidentEntity)

    @Query("UPDATE incidents SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE incidents SET isBroadcast = 1 WHERE id = :id")
    suspend fun markBroadcast(id: String)

    @Query("UPDATE incidents SET latitude = :latitude, longitude = :longitude WHERE id = :id")
    suspend fun updateCoordinates(id: String, latitude: Double?, longitude: Double?)

    @Query(
        """
        UPDATE incidents SET verified = 1, isRejected = 0, verifiedBy = :by, verifiedAtMillis = :at
        WHERE id = :id
        """,
    )
    suspend fun markVerified(id: String, by: String, at: Long)

    @Query(
        """
        UPDATE incidents SET verified = 0, isRejected = 1, verifiedBy = :by, verifiedAtMillis = :at
        WHERE id = :id
        """,
    )
    suspend fun markRejected(id: String, by: String, at: Long)

    @Delete
    suspend fun delete(incident: IncidentEntity)
}
