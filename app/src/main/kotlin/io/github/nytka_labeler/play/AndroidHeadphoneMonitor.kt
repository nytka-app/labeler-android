package io.github.nytka_labeler.play

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Watches the audio outputs for headphones; the callback keeps [connected] current while the app lives. */
class AndroidHeadphoneMonitor(
    context: Context,
) : HeadphoneMonitor {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val mutable = MutableStateFlow(check())
    override val connected: StateFlow<Boolean> = mutable.asStateFlow()

    init {
        audio.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    mutable.value = check()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    mutable.value = check()
                }
            },
            null,
        )
    }

    private fun check(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }

    private companion object {
        val HEADPHONE_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )
    }
}
