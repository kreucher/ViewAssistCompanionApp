package com.msp1974.vacompanion.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.ImageProcessing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Camera(val context: Context, val config: APPConfig) {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    
    private val motionEngine = MotionDetectionEngine()
    val motionFlow: SharedFlow<MotionResult> = motionEngine.motionFlow

    private var isRunning: Boolean = false
    private var isStarting: Boolean = false
    
    private var lastDetection: Long = 0
    private val settleDelay: Long = 5000
    private var settleDelayJob: Job? = null

    init {
        // Setup motion detection flow subscriber
        scope.launch {
            motionFlow.collect { result ->
                if (result.hasMotion) {
                    if (System.currentTimeMillis() - lastDetection > MOTION_INTERVAL) {
                        Timber.d("Motion detected (CameraX Engine)")
                        config.eventBroadcaster.notifyEvent(Event("motion", "", ""))
                        lastDetection = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    companion object {
        const val MOTION_INTERVAL = 10000
    }

    fun setSensitivity(sensitivity: Int) {
        motionEngine.setSensitivity(sensitivity)
    }

    fun startCamera() {
        if (isRunning || isStarting) return

        val lifecycleOwner = context as? LifecycleOwner
        if (lifecycleOwner == null) {
            Timber.e("Camera: Context is not a LifecycleOwner. Cannot start CameraX.")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Timber.e("Camera: Permission not granted")
            return
        }

        isStarting = true
        Timber.i("Starting CameraX for motion detection")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        setSensitivity(config.motionDetectionSensitivity)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                // Ensure we are running on the main thread for binding
                bindCameraUseCases(lifecycleOwner)
                isRunning = true
                isStarting = false
                
                // Settle motion detection to reduce false detections at start
                settleDelayJob?.cancel()
                settleDelayJob = scope.launch {
                    delay(settleDelay)
                }
                
            } catch (e: Exception) {
                Timber.e("Camera: Error starting CameraX: $e")
                isRunning = false
                isStarting = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        val cameraProvider = cameraProvider ?: return

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(resolutionSelector)

        // Interop for low-light optimizations (Antibanding)
        Camera2Interop.Extender(analysisBuilder).apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }
        
        imageAnalysis = analysisBuilder.build().also {
            it.setAnalyzer(cameraExecutor) { image ->
                processImageProxy(image)
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            
            // Check if we should actually be running
            if (!config.enableMotionDetection || config.cameraStreamActive) {
                Timber.w("Camera about to bind but motion detection disabled or stream active, skipping")
                isRunning = false
                isStarting = false
                return
            }

            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            
            // Apply low-light optimizations (Exposure compensation)
            val cameraControl = camera.cameraControl
            val cameraInfo = camera.cameraInfo
            val camera2CameraInfo = Camera2CameraInfo.from(cameraInfo)
            
            val range = camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            if (range != null && (range.lower != 0 || range.upper != 0)) {
                cameraControl.setExposureCompensationIndex(range.upper)
            }
            
            // Try to enable Night Mode (Scene Mode) via Interop if supported
            val sceneModes = camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            if (sceneModes?.contains(CaptureRequest.CONTROL_SCENE_MODE_NIGHT) == true) {
                // To set scene mode, we need to set CONTROL_MODE to USE_SCENE_MODE as well
                // This is best done via Interop on the UseCase
                val currentExtender = Camera2Interop.Extender(analysisBuilder)
                currentExtender.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                currentExtender.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
            }
            
            Timber.d("CameraX bound to lifecycle successfully")
        } catch (e: Exception) {
            Timber.e("Camera: Use case binding failed: $e")
        }
    }

    private fun processImageProxy(image: ImageProxy) {
        try {
            // Check if we are in settle delay
            if (settleDelayJob != null && settleDelayJob!!.isActive) {
                return
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            
            // Extract luma data while respecting stride
            val lumaData = ByteArray(width * height)
            if (rowStride == width) {
                // Contiguous buffer
                buffer.get(lumaData)
            } else {
                // Buffer with padding
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(lumaData, row * width, width)
                }
            }
            
            // Use our luma decoder (which now includes low-light boost)
            val luma = ImageProcessing.decodeYUV420SPtoLuma(lumaData, width, height)
            val rotation = image.imageInfo.rotationDegrees

            scope.launch {
                motionEngine.processFrame(luma, width, height, rotation)
            }
        } catch (e: Exception) {
            Timber.e("Camera: Error processing image: $e")
        } finally {
            image.close()
        }
    }

    suspend fun stopCamera() {
        Timber.i("Stopping CameraX motion detection")
        isRunning = false
        isStarting = false
        settleDelayJob?.cancel()
        
        // We must unbind on main thread for CameraX
        withContext(Dispatchers.Main) {
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Timber.e("Camera: Error unbinding: $e")
            }
            cameraProvider = null
            imageAnalysis = null
        }
    }
}
