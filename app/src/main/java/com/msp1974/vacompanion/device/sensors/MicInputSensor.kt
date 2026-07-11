package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.msp1974.vacompanion.audio.MicrophoneInput
import kotlinx.coroutines.launch

class MicInputSensor(private val context: Context) : Sensor {

    companion object {
        internal val basicSensor = Sensor.BasicSensor(
            "mic_input",
            type = -4, // Custom type
            name = "Microphone Input Sensor",
        )
    }

    override var onUpdate: ((String, Any) -> Unit)? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var currentState = MicInputState()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            updateInputs()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            updateInputs()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        updateInputs()
    }

    private fun updateInputs() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        
        val filteredDevices = devices.filter { device ->
            MicrophoneInput.getDeviceTypeName(device.type) != "Other"
        }

        val inputNames = filteredDevices.map { device ->
            "${device.productName} (${MicrophoneInput.getDeviceTypeName(device.type)})"
        }.distinct()
        
        val activeInput = MicrophoneInput.activeMicInput

        val newState = MicInputState(
            availableInputs = inputNames,
            activeInput = activeInput
        )

        if (newState != currentState) {
            currentState = newState
            Sensor.sensorWorkerScope.launch {
                onSensorUpdated(basicSensor.id, currentState)
            }
        }
    }

    override fun hasSensor(context: Context): Boolean = true

    override fun requiredPermissions(): Array<String> = emptyArray()

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return listOf(basicSensor)
    }

    override suspend fun requestSensorUpdate(context: Context) {
        updateInputs()
    }

    override fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }
}
