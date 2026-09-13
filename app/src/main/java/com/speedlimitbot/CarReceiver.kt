package com.speedlimitbot

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts/stops the radar when the phone pairs with a car head unit over Bluetooth — the
 * signal that reliably precedes an Android Auto session. Projection end is also caught by
 * CarConnection inside the service.
 */
class CarReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (!isCarAudio(intent)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> AlertService.start(ctx)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> AlertService.stop(ctx)
        }
    }

    @Suppress("DEPRECATION")
    private fun isCarAudio(intent: Intent): Boolean {
        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val cls = runCatching { device?.bluetoothClass?.deviceClass }.getOrNull() ?: return false
        return cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
            cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
    }
}
