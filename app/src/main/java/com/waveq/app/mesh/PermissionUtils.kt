package com.waveq.app.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionUtils {

    /**
     * What Nearby Connections itself needs to advertise, discover and exchange
     * payloads - and nothing else.
     *
     * RECORD_AUDIO is deliberately NOT in this set. Voice notes are an optional
     * feature on top of the mesh, but while the microphone was a member of this
     * array a user who declined it could never start the transport at all: the
     * same [hasAllMeshPermissions] check gates MeshViewModel.startMesh AND
     * SosBeaconService's transport start, so declining the mic silently disabled
     * SOS broadcasting. Ask for the microphone at the mic button instead - see
     * [requiredVoicePermissions].
     */
    fun requiredMeshPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return perms.toTypedArray()
    }

    fun hasAllMeshPermissions(context: Context): Boolean =
        requiredMeshPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Microphone, for voice notes only. Requested lazily when the mic button is first pressed. */
    fun requiredVoicePermissions(): Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    fun hasVoicePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Location for the beacon fix, plus notifications for the persistent "SOS
     * active" notice.
     *
     * Location is the only part an SOS genuinely cannot work without.
     * POST_NOTIFICATIONS is requested alongside it but is NOT required - see
     * [hasRequiredSosPermissions] - because the foreground-service notification
     * is shown by the system regardless, and treating a declined notification
     * prompt as a hard failure left the SOS button silently dead.
     */
    fun requiredSosPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    /** The subset an SOS cannot run without: a location fix. Coarse alone is enough to start. */
    fun hasRequiredSosPermissions(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAllSosPermissions(context: Context): Boolean =
        requiredSosPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
