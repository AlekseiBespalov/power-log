package app.powerlog.platform.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.platform.ui.AndroidUiContext

class AndroidPermissionGate(
    private val context: Context,
    private val ui: AndroidUiContext,
    private val handler: Handler,
) : PermissionGate {
    private val main = Handler(Looper.getMainLooper())
    private val asked = context.getSharedPreferences(MARKERS, Context.MODE_PRIVATE)
    private var requesting = false

    override fun status(reply: Reply<PermissionStates>) {
        handler.post { reply.ok(states()) }
    }

    override fun request(kinds: Set<PermissionKind>, reply: Reply<PermissionStates>) {
        if (requesting) return post { reply.failed(PortError(PortErrorKind.busy)) }
        val permissions = kinds.flatMap(::permissionsOf).distinct()
        if (permissions.isEmpty()) return status(reply)
        val asking =
            PermissionKind.entries.filter {
                permissionsOf(it).let { own -> own.isNotEmpty() && own.all(permissions::contains) }
            }
        requesting = true
        main.post {
            val shown =
                ui.requestPermissions(permissions) {
                    handler.post {
                        requesting = false
                        val markers = asked.edit()
                        asking.forEach { markers.putBoolean(marker(it), true) }
                        markers.apply()
                        reply.ok(states())
                    }
                }
            if (!shown)
                handler.post {
                    requesting = false
                    reply.failed(PortError(PortErrorKind.uiUnavailable))
                }
        }
    }

    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    private fun states(): PermissionStates {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val location = fine || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val locations = context.getSystemService(LocationManager::class.java)
        return PermissionStates(
            bluetooth =
                if (Build.VERSION.SDK_INT < 31) PermissionState.granted
                else state(PermissionKind.bluetooth, permissionsOf(PermissionKind.bluetooth).all(::granted)),
            location = state(PermissionKind.location, location),
            preciseLocation = if (location) fine else null,
            locationServicesOn = locations?.let(LocationManagerCompat::isLocationEnabled),
            notifications =
                if (Build.VERSION.SDK_INT < 33) null
                else state(PermissionKind.notifications, granted(Manifest.permission.POST_NOTIFICATIONS)),
        )
    }

    private fun state(kind: PermissionKind, granted: Boolean): PermissionState =
        when {
            granted -> PermissionState.granted
            asked.getBoolean(marker(kind), false) -> PermissionState.denied
            else -> PermissionState.notDetermined
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MARKERS = "permissions"

        fun marker(kind: PermissionKind): String = "${kind.name}Requested"

        fun permissionsOf(kind: PermissionKind): List<String> =
            when (kind) {
                PermissionKind.bluetooth ->
                    if (Build.VERSION.SDK_INT >= 31)
                        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                    else locationPermissions
                PermissionKind.location -> locationPermissions
                PermissionKind.notifications ->
                    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            }

        val locationPermissions =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
