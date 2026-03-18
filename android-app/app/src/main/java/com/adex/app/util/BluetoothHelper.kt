package com.adex.app.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

class BluetoothHelper(private val context: Context) {
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val adapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }

    companion object {
        private val discoveredDevices = ConcurrentHashMap<String, String>()

        fun addDiscoveredDevice(address: String, name: String?) {
            discoveredDevices[address] = name ?: "Unknown"
        }

        fun getDiscoveredDevices(): Map<String, String> {
            return discoveredDevices
        }

        fun clearDiscoveredDevices() {
            discoveredDevices.clear()
        }
    }

    fun getStatus(): Map<String, Any> {
        val enabled = adapter?.isEnabled ?: false
        val bondedDevices = if (enabled) {
            getBondedDevices().map { 
                mapOf("name" to (it.name ?: "Unknown"), "address" to it.address, "type" to getDeviceType(it))
            }
        } else {
            emptyList<Map<String, String>>()
        }

        val discovered = getDiscoveredDevices().map { (addr, name) -> 
            mapOf("name" to name, "address" to addr)
        }

        return mapOf(
            "enabled" to enabled,
            "address" to (adapter?.address ?: "unknown"),
            "name" to (adapter?.name ?: "unknown"),
            "state" to when (adapter?.state) {
                BluetoothAdapter.STATE_OFF -> "OFF"
                BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                BluetoothAdapter.STATE_ON -> "ON"
                BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                else -> "UNKNOWN"
            },
            "bondedDevicesCount" to bondedDevices.size,
            "bondedDevices" to bondedDevices,
            "discoveredDevices" to discovered,
            "isDiscovering" to (adapter?.isDiscovering ?: false)
        )
    }

    @SuppressLint("MissingPermission")
    fun setEnabled(enable: Boolean): Boolean {
        return if (enable) {
            adapter?.enable() ?: false
        } else {
            adapter?.disable() ?: false
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        if (adapter == null || !adapter!!.isEnabled) return false
        if (adapter!!.isDiscovering) adapter!!.cancelDiscovery()
        clearDiscoveredDevices()
        return adapter!!.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): Set<BluetoothDevice> {
        return adapter?.bondedDevices ?: emptySet()
    }

    private fun getDeviceType(device: BluetoothDevice): String {
        return when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
            BluetoothDevice.DEVICE_TYPE_LE -> "LE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
            else -> "UNKNOWN"
        }
    }
}
