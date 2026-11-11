package com.tom.magify.launcher

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

const val BLUETOOTH_PROFILE_KEY = "bluetooth_profile_id"
const val BLUETOOTH_DEVICE_KEY = "bluetooth_device_name"

/**
 * Woken by the system on every Bluetooth connection / disconnection, even
 * with the launcher not running: connecting to a device a profile lists in
 * "bluetoothDevices" (the car's "Media Display") switches to that profile
 * until it disconnects.
 */
class BluetoothProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        // Reading the name needs BLUETOOTH_CONNECT; without it, nothing to match.
        val name = runCatching { device?.name }.getOrNull() ?: return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> BluetoothProfiles.onConnected(context, name)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> BluetoothProfiles.onDisconnected(context, name)
        }
    }
}

/** The Bluetooth-triggered profile override, stored next to the manual choice. */
object BluetoothProfiles {

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    fun onConnected(context: Context, deviceName: String) {
        val profile = ProfileConfig.load(context).profileForBluetoothDevice(deviceName) ?: return
        prefs(context).edit()
            .putString(BLUETOOTH_PROFILE_KEY, profile.id)
            .putString(BLUETOOTH_DEVICE_KEY, deviceName)
            .apply()
    }

    fun onDisconnected(context: Context, deviceName: String) {
        val prefs = prefs(context)
        if (!deviceName.equals(prefs.getString(BLUETOOTH_DEVICE_KEY, null), ignoreCase = true)) return
        clear(context)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(BLUETOOTH_PROFILE_KEY).remove(BLUETOOTH_DEVICE_KEY).apply()
    }

    /**
     * Re-aligns the override with what's actually connected right now: a
     * reboot or Bluetooth switched off mid-trip would otherwise leave the
     * phone stuck in the car profile, and a launcher installed while already
     * connected would never notice.
     */
    fun resync(context: Context) {
        if (!hasPermission(context)) return
        val app = context.applicationContext
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) {
            clear(app)
            return
        }
        val profiles = listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
        val connectedNames = mutableSetOf<String>()
        var remaining = profiles.size
        profiles.forEach { profileType ->
            val requested = adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runCatching { proxy.connectedDevices.mapNotNullTo(connectedNames) { it.name } }
                    adapter.closeProfileProxy(profile, proxy)
                    if (--remaining == 0) apply(app, connectedNames)
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }, profileType)
            if (!requested && --remaining == 0) apply(app, connectedNames)
        }
    }

    private fun apply(context: Context, connectedNames: Set<String>) {
        val config = ProfileConfig.load(context)
        val match = connectedNames.firstNotNullOfOrNull { name ->
            config.profileForBluetoothDevice(name)?.let { name to it }
        }
        val prefs = prefs(context)
        if (match == null) {
            if (prefs.contains(BLUETOOTH_PROFILE_KEY)) clear(context)
        } else if (prefs.getString(BLUETOOTH_PROFILE_KEY, null) != match.second.id) {
            onConnected(context, match.first)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PROFILE_PREFS_NAME, Context.MODE_PRIVATE)
}
