package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout
import com.msp1974.vacompanion.ui.theme.CustomColours

@Composable
fun DeviceInfoLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deviceInfo = remember { viewModel.deviceInfo }
    
    // Summary of Audio Capabilities
    val audioSummary = buildString {
        if (deviceInfo.features.audio.autoGainControl) append("AGC ")
        if (deviceInfo.features.audio.noiseSuppression) append("NS ")
        if (deviceInfo.features.audio.acousticEchoCancellation) append("AEC")
    }.trim()

    MenuLayout(
        title = "Device Info",
        level = 1,
        onClose = onBack,
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            item { InfoRow("Device", deviceInfo.hardware.deviceName) }
            item { InfoRow("App Version", deviceInfo.software.appVersion) }
            item { InfoRow("Android Version", deviceInfo.software.release) }
            item { InfoRow("WebView Version", deviceInfo.software.webViewVersion) }
            
            item { InfoRow("Battery", if (deviceInfo.hardware.hasBattery) "Yes" else "No", color = if (deviceInfo.hardware.hasBattery) CustomColours.GREEN else CustomColours.AMBER) }
            item { InfoRow("Front Camera", if (deviceInfo.hardware.hasFrontCamera) "Yes" else "No", color = if (deviceInfo.hardware.hasFrontCamera) CustomColours.GREEN else CustomColours.AMBER) }
            item { InfoRow("DND Support", if (deviceInfo.features.supportsDND) "Yes" else "No", color = if (deviceInfo.features.supportsDND) CustomColours.GREEN else CustomColours.AMBER) }

            item { InfoRow("Accelerometer Sensor", if (deviceInfo.hardware.hasAccelerometer) "Yes" else "No", color = if (deviceInfo.hardware.hasAccelerometer) CustomColours.GREEN else CustomColours.AMBER) }
            item { InfoRow("Light Sensor", if (deviceInfo.hardware.hasLightSensor) "Yes" else "No", color = if (deviceInfo.hardware.hasLightSensor) CustomColours.GREEN else CustomColours.AMBER) }
            item { InfoRow("Proximity Sensor", if (deviceInfo.hardware.hasProximitySensor) "Yes" else "No", color = if (deviceInfo.hardware.hasProximitySensor) CustomColours.GREEN else CustomColours.AMBER) }
            item { InfoRow("Proximity Sensor Type", deviceInfo.hardware.proximitySensorType.replaceFirstChar { it.uppercase() }) }

            item { InfoRow("Available Sensors", deviceInfo.hardware.sensors.joinToString(separator = ", ") { it.name.lowercase().replaceFirstChar { it.uppercase() } }) }

            item { InfoRow("Audio Enhancements", audioSummary.ifEmpty { "None supported" }) }
        }
    }
}

@Composable
private fun InfoRow(name: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$name: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}
