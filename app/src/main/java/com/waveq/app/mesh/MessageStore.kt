package com.waveq.app.mesh

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

private const val DB_NAME = "waveq_mesh_store.db"
private const val STORE_CAPACITY = 500
const val SOS_STORE_TTL_MS = 6 * 60 * 60 * 1000L
const val MESSAGE_STORE_TTL_MS = 24 * 60 * 60 * 1000L

/**
 * A single envelope this device is carrying for store-and-forward delivery.
 *
 * [envelopeBytes] is the exact wire encoding of the [MeshEnvelope] this device
 * would relay - stored opaque, exactly as received/sent, so a relay carrying a
 * message it cannot decrypt still cannot read it. [hopCount] inside those bytes
 * already reflects the relay increment applied when this device first saw the
 * envelope, so replaying it later must not increment it again.
 *
 * [beaconId]/[sosSequence] are only populated for SOS rows, letting the store
 * find-and-replace the previous row for the same beacon rather than keeping a
 * stale trail of superseded positions.
 */
@Entity(tableName = "pending_envelopes")
data class PendingEnvelopeEntity(
    @PrimaryKey val messageId: String,
    val channelId: String,
    val envelopeBytes: ByteArray,
    val hopCount: Int,
    val isSos: Boolean,
    val beaconId: String?,
    val sosSequence: Int?,
    val firstSeenAt: Long,
    val expiresAt: Long,
    val deliveredToPeerIds: String,
)

@Dao
interface PendingEnvelopeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingEnvelopeEntity)

    @Query("SELECT * FROM pending_envelopes WHERE beaconId = :beaconId LIMIT 1")
    suspend fun findByBeaconId(beaconId: String): PendingEnvelopeEntity?

    @Query("DELETE FROM pending_envelopes WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("SELECT COUNT(*) FROM pending_envelopes")
    suspend fun count(): Int

    @Query(
        """
        DELETE FROM pending_envelopes WHERE messageId IN (
            SELECT messageId FROM pending_envelopes WHERE isSos = 0 ORDER BY firstSeenAt ASC LIMIT :n
        )
        """,
    )
    suspend fun evictOldestNonSos(n: Int)

    @Query("DELETE FROM pending_envelopes WHERE expiresAt <= :now")
    suspend fun purgeExpired(now: Long)

    /** SOS rows first (isSos DESC), oldest-first within each group. */
    @Query("SELECT * FROM pending_envelopes WHERE expiresAt > :now ORDER BY isSos DESC, firstSeenAt ASC")
    suspend fun allUnexpired(now: Long): List<PendingEnvelopeEntity>

    @Query("UPDATE pending_envelopes SET deliveredToPeerIds = :deliveredToPeerIds WHERE messageId = :messageId")
    suspend fun updateDelivered(messageId: String, deliveredToPeerIds: String)
}

@Database(entities = [PendingEnvelopeEntity::class], version = 1, exportSchema = false)
abstract class MessageStoreDatabase : RoomDatabase() {
    abstract fun pendingEnvelopeDao(): PendingEnvelopeDao
}

/**
 * Store-and-forward buffer: every envelope this device relays (or originates)
 * is kept here, still fully opaque if this device can't decrypt it, so it can
 * be replayed to a peer this device meets later - carrying it "across time"
 * rather than only across devices currently in range.
 */
class MessageStore(context: Context) {

    data class StoreStats(val totalCount: Int, val sosCount: Int)

    private val db = Room.databaseBuilder(
        context.applicationContext,
        MessageStoreDatabase::class.java,
        DB_NAME,
    ).build()
    private val dao = db.pendingEnvelopeDao()

    /**
     * Records [envelope] for later replay. Returns true if a row was written
     * (a new envelope, or a fresher SOS sequence replacing a stale one); false
     * if it was dropped (malformed SOS payload, or a stale/duplicate SOS
     * sequence that doesn't advance what this device is carrying).
     */
    suspend fun record(envelope: MeshEnvelope, now: Long = System.currentTimeMillis()): Boolean {
        val isSos = envelope.channelId == SOS_CHANNEL_ID
        val entity = if (isSos) {
            buildSosEntity(envelope, now) ?: return false
        } else {
            PendingEnvelopeEntity(
                messageId = envelope.messageId,
                channelId = envelope.channelId,
                envelopeBytes = MeshSerialization.encodeEnvelope(envelope),
                hopCount = envelope.hopCount,
                isSos = false,
                beaconId = null,
                sosSequence = null,
                firstSeenAt = now,
                expiresAt = now + MESSAGE_STORE_TTL_MS,
                deliveredToPeerIds = "",
            )
        }

        enforceCapacity()
        dao.insert(entity)
        return true
    }

    /** Only the highest sequence per beaconId is kept - a fresher position replaces the stored row. */
    private suspend fun buildSosEntity(envelope: MeshEnvelope, now: Long): PendingEnvelopeEntity? {
        val beacon = try {
            MeshSerialization.decodeSosBeacon(envelope.payload)
        } catch (e: Exception) {
            return null
        }
        val existing = dao.findByBeaconId(beacon.beaconId)
        if (existing != null && existing.sosSequence != null && beacon.sequence <= existing.sosSequence) {
            return null // not newer than what we already carry for this beacon
        }
        existing?.let { dao.deleteById(it.messageId) }
        return PendingEnvelopeEntity(
            messageId = envelope.messageId,
            channelId = envelope.channelId,
            envelopeBytes = MeshSerialization.encodeEnvelope(envelope),
            hopCount = envelope.hopCount,
            isSos = true,
            beaconId = beacon.beaconId,
            sosSequence = beacon.sequence,
            firstSeenAt = existing?.firstSeenAt ?: now,
            expiresAt = now + SOS_STORE_TTL_MS,
            // A fresher position is new information even for peers who already
            // saw the stale one - resend it rather than treating them as done.
            deliveredToPeerIds = "",
        )
    }

    /** Unexpired envelopes not yet delivered to [endpointId], SOS first, oldest-first within each group. */
    suspend fun pendingFor(endpointId: String, now: Long = System.currentTimeMillis()): List<PendingEnvelopeEntity> =
        dao.allUnexpired(now).filter { endpointId !in it.deliveredToPeerIds.split(",").filter(String::isNotEmpty) }

    suspend fun markDelivered(entity: PendingEnvelopeEntity, endpointId: String) {
        val ids = entity.deliveredToPeerIds.split(",").filter { it.isNotEmpty() }.toMutableSet()
        if (!ids.add(endpointId)) return
        dao.updateDelivered(entity.messageId, ids.joinToString(","))
    }

    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) = dao.purgeExpired(now)

    suspend fun stats(now: Long = System.currentTimeMillis()): StoreStats {
        val all = dao.allUnexpired(now)
        return StoreStats(totalCount = all.size, sosCount = all.count { it.isSos })
    }

    /** Never evicts an unexpired SOS row - only makes room among the non-SOS rows. */
    private suspend fun enforceCapacity() {
        val overflow = dao.count() - (STORE_CAPACITY - 1)
        if (overflow > 0) dao.evictOldestNonSos(overflow)
    }
}
