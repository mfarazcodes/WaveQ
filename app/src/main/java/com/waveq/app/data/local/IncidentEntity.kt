package com.waveq.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey val id: String,
    val type: String,
    val location: String,
    val description: String,
    val severity: String,
    val reportedAt: String,
    val photoUri: String? = null,
    val audioPath: String? = null,
    val verified: Boolean = false,
    val isSynced: Boolean = false
)