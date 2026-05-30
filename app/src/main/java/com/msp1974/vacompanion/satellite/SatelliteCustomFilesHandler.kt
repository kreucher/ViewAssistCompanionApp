package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import com.msp1974.vacompanion.wakeword.DownloadStatus
import com.msp1974.vacompanion.wakeword.WakeWordDownloader
import com.msp1974.vacompanion.wakeword.WakeWordType
import kotlinx.serialization.json.*
import timber.log.Timber

class SatelliteCustomFilesHandler(
    val context: Context,
    val config: APPConfig,
    val viewModel: VAViewModel? = null
) {
    private var wakeWordDownloader = WakeWordDownloader(context, config)

    suspend fun downloadCustomWakeWords(force: Boolean = false): Boolean {
        var hasDownloaded = false
        val customFiles = config.customFiles as? JsonObject ?: return false

        for (wakeWordTypeEntry in customFiles) {
            val typeKey = wakeWordTypeEntry.key
            
            val wakeWordModelType = if (typeKey == WakeWordType.MICROWAKEWORD.toString().lowercase()) {
                WakeWordType.MICROWAKEWORD 
            } else if (typeKey == WakeWordType.OPENWAKEWORD.toString().lowercase()) {
                WakeWordType.OPENWAKEWORD
            } else {
                continue
            }

            // Handle both JsonObject (map) and JsonArray (list)
            val wakeWordEntries = when (val value = wakeWordTypeEntry.value) {
                is JsonObject -> value.entries.map { it.key to (it.value as? JsonObject) }
                is JsonArray -> value.map { it.jsonPrimitive.content to null as JsonObject? }
                else -> continue
            }

            for ((name, entryConfig) in wakeWordEntries) {
                val configExtensions = entryConfig?.get("extensions")?.jsonArray?.map { it.jsonPrimitive.content }
                
                val extensions = configExtensions ?: when (wakeWordModelType) {
                    WakeWordType.MICROWAKEWORD -> listOf("json", "tflite")
                    WakeWordType.OPENWAKEWORD -> listOf("onnx", "tflite")
                }

                if (wakeWordModelType == WakeWordType.OPENWAKEWORD) {
                    val missingExtensions = mutableListOf<String>()
                    for (ext in extensions) {
                        if (force || !wakeWordDownloader.fileExists(wakeWordModelType, "$name.$ext")) {
                            missingExtensions.add(ext)
                        }
                    }
                    
                    if (missingExtensions.isNotEmpty()) {
                        Timber.i("Download of $name ($wakeWordModelType) files needed: $missingExtensions")
                        val displayName = name.replace("_", " ").capitalizeWords()
                        wakeWordDownloader.downloadWakeWord(wakeWordModelType, name, missingExtensions).collect { status ->
                            handleDownloadStatus(displayName, status)
                        }
                        hasDownloaded = true
                    }
                } else {
                    if (!extensions.contains("json") || !extensions.contains("tflite")) {
                        Timber.w("Skipping microWakeWord $name: required extensions (json, tflite) not fully specified in config")
                        continue
                    }

                    var downloadNeeded = force
                    if (!downloadNeeded) {
                        for (ext in extensions) {
                            if (!wakeWordDownloader.fileExists(wakeWordModelType, "$name.$ext")) {
                                downloadNeeded = true
                                break
                            }
                        }
                    }

                    if (downloadNeeded) {
                        Timber.i("Download of $name ($wakeWordModelType) needed")
                        val displayName = name.replace("_", " ").capitalizeWords()
                        wakeWordDownloader.downloadWakeWord(wakeWordModelType, name, extensions).collect { status ->
                            handleDownloadStatus(displayName, status)
                        }
                        hasDownloaded = true
                    }
                }
            }
        }
        return hasDownloaded
    }

    suspend fun syncAllCustomFiles() {
        val customFiles = config.customFiles as? JsonObject ?: return

        // 1. Sync Wake Words and Cleanup Orphans
        val configuredWakeWords = mutableMapOf<WakeWordType, Set<String>>()
        
        for (wakeWordTypeEntry in customFiles) {
            val typeKey = wakeWordTypeEntry.key
            if (typeKey == WakeWordType.MICROWAKEWORD.toString().lowercase() || typeKey == WakeWordType.OPENWAKEWORD.toString().lowercase()) {
                val type = if (typeKey == WakeWordType.MICROWAKEWORD.toString().lowercase()) WakeWordType.MICROWAKEWORD else WakeWordType.OPENWAKEWORD
                
                val names = when (val value = wakeWordTypeEntry.value) {
                    is JsonObject -> value.keys
                    is JsonArray -> value.map { it.jsonPrimitive.content }.toSet()
                    else -> emptySet()
                }
                configuredWakeWords[type] = names
            }
        }

        // Cleanup orphaned wake words
        WakeWordType.entries.forEach { type ->
            val localFiles = wakeWordDownloader.listWakeWords(type)
            val configuredForType = configuredWakeWords[type] ?: emptySet()
            localFiles.forEach { localName ->
                if (!configuredForType.contains(localName)) {
                    Timber.i("Deleting orphaned wake word: $localName ($type)")
                    wakeWordDownloader.deleteWakeWord(type, localName)
                }
            }
        }

        // Download/Update all configured wake words
        downloadCustomWakeWords(force = true)

        // 2. Sync Sounds and Alarms
        syncGenericFiles(customFiles, WakeWordDownloader.SOUNDS_DIR)
        syncGenericFiles(customFiles, WakeWordDownloader.ALARMS_DIR)
    }

    private suspend fun syncGenericFiles(customFiles: JsonObject, subDir: String) {
        val configuredFiles = (customFiles[subDir] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
        val localFiles = wakeWordDownloader.listCustomFiles(subDir)

        // Cleanup orphans
        localFiles.forEach { localName ->
            if (!configuredFiles.contains(localName)) {
                Timber.i("Deleting orphaned $subDir: $localName")
                wakeWordDownloader.deleteCustomFile(subDir, localName)
            }
        }

        // Download all configured
        configuredFiles.forEach { fileName ->
            Timber.i("Syncing $subDir: $fileName")
            val displayName = fileName.replace("_", " ").capitalizeWords()
            wakeWordDownloader.downloadCustomFile(subDir, fileName).collect { status ->
                handleDownloadStatus(displayName, status)
            }
        }
    }

    private fun handleDownloadStatus(displayName: String, status: DownloadStatus) {
        when (status) {
            is DownloadStatus.Progress -> viewModel?.setDownloadProgress(displayName, status.progress)
            is DownloadStatus.Success, is DownloadStatus.Error -> viewModel?.clearDownloadProgress()
        }
    }
}
