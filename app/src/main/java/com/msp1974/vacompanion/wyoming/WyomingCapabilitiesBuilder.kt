package com.msp1974.vacompanion.wyoming

import com.msp1974.vacompanion.device.DeviceCapabilitiesData
import com.msp1974.vacompanion.device.DeviceCapabilitiesManager
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import kotlin.collections.component1
import kotlin.collections.component2

class WyomingCapabilitiesBuilder(private val config: APPConfig, private val deviceInfo: DeviceCapabilitiesData) {

    @OptIn(ExperimentalSerializationApi::class)
    fun buildInfo(): JsonObject {
        val baseCapabilities = DeviceCapabilitiesManager.toJson(deviceInfo)
        val availableWakeSounds = config.availableWakeSounds
        val availableAlarms = config.availableAlarms

        return buildJsonObject {
           baseCapabilities.forEach { (key, value) -> put(key, value) }
            putJsonArray("wake_sounds") {
                availableWakeSounds.forEach { sound ->
                    add(buildJsonObject {
                        put("id", sound.id)
                        put("name", sound.name)
                    })
                }
            }
            putJsonArray("alarms") {
                availableAlarms.forEach { alarm ->
                    add(buildJsonObject {
                        put("id", alarm.id)
                        put("name", alarm.name)
                    })
                }
            }
        }
    }
}