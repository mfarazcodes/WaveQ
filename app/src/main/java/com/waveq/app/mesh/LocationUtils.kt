package com.waveq.app.mesh

import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** One-shot high-accuracy location fetch, suspending until a fix (or failure) arrives. */
suspend fun FusedLocationProviderClient.awaitCurrentLocation(): Location? =
    suspendCancellableCoroutine { cont ->
        val cancellationSource = CancellationTokenSource()
        cont.invokeOnCancellation { cancellationSource.cancel() }
        try {
            getCurrentLocation(
                CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).build(),
                cancellationSource.token,
            )
                .addOnSuccessListener { location -> if (cont.isActive) cont.resume(location) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        }
    }

/** Last known location, if any - fast, no active GPS fix requested. Used for critical-alert distance display. */
suspend fun FusedLocationProviderClient.awaitLastLocation(): Location? =
    suspendCancellableCoroutine { cont ->
        try {
            lastLocation
                .addOnSuccessListener { location -> if (cont.isActive) cont.resume(location) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        }
    }
