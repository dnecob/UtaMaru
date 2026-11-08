package org.example.voicepitchlooper

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Detects a usable external audio output, not merely a paired Bluetooth device.
 * Connection != actual AudioTrack routing. AudioEngine verifies the active route after playback.
 */
object AudioOutputSafety {
    fun hasConnectedExternalOutput(context: Context): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isExternal(it.type) }
        } catch (_: SecurityException) {
            false // Warn conservatively if routing information is restricted.
        }
    }

    private fun isExternal(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
        else -> false
    }
}
