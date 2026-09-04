package com.waveq.app.model

import android.location.Location

data class SafeZone(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val capacity: Int,
    val currentOccupancy: Int,
    val hasMedicalSupport: Boolean = true,
    val hasFoodSupplies: Boolean = true,
) {
    fun distanceTo(userLat: Double, userLng: Double): Float {
        val userLoc = Location("user").apply {
            latitude = userLat
            longitude = userLng
        }
        val shelterLoc = Location("shelter").apply {
            latitude = this@SafeZone.latitude
            longitude = this@SafeZone.longitude
        }
        return userLoc.distanceTo(shelterLoc) // Distance in meters
    }
}

val defaultSafeZones = listOf(
    SafeZone(
        id = "SZ-01",
        name = "District Sports Stadium Complex",
        address = "Civil Lines, Near Collectorate",
        latitude = 29.3724,
        longitude = 78.1358,
        capacity = 1200,
        currentOccupancy = 340,
    ),
    SafeZone(
        id = "SZ-02",
        name = "Government Inter College (GIC) Relief Center",
        address = "Station Road, North Sector",
        latitude = 29.3812,
        longitude = 78.1420,
        capacity = 600,
        currentOccupancy = 180,
    ),
    SafeZone(
        id = "SZ-03",
        name = "Community Health Emergency Center",
        address = "Kiratpur Bypass Road",
        latitude = 29.3640,
        longitude = 78.1215,
        capacity = 450,
        currentOccupancy = 410,
    )
)