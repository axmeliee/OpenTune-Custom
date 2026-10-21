package com.opentune.data.addons

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** A conservative output gate. Bluetooth is unknown without a reliable codec reading. */
object AddonOutput {
    fun init(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        AddonStore.outputAllowed = { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in supported } }
    }
    private val supported = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_DOCK)
}
