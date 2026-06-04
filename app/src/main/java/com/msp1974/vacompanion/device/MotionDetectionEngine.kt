package com.msp1974.vacompanion.device

import android.graphics.RectF
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import kotlin.math.abs

data class MotionResult(
    val hasMotion: Boolean,
    val boundingBoxes: List<RectF>,
    val motionIntensity: Float, // 0.0 to 1.0
    val width: Int,
    val height: Int,
    val rotation: Int = 0
)

class MotionDetectionEngine(
    private val detectionWidth: Int = 160,
    private val detectionHeight: Int = 120
) {
    private var backgroundModel: IntArray? = null
    private var alpha = 0.05f // Learning rate for background model
    private var motionThreshold = 25 // Luma difference threshold
    private var minBlobSize = 64 // Minimum pixels in a block to consider as motion
    
    private val _motionFlow = MutableSharedFlow<MotionResult>(replay = 0)
    val motionFlow = _motionFlow.asSharedFlow()

    fun stepRange(value: Int, min: Int, max: Int, steps: Int = 100, invert: Boolean = true): Int {
        return stepRange(value, min.toFloat(), max.toFloat()).toInt()
    }

    fun stepRange(value: Int, min: Float, max: Float, steps: Int = 100, invert: Boolean = true): Float {
        val step = (max-min) / steps
        return if (invert) {
            max - (step * value)
        } else {
            min + (step * value)
        }
    }

    fun setSensitivity(sensitivity: Int) {
        Timber.i("Motion sensitivity updated to $sensitivity")
        // Higher sensitivity = lower threshold
        // Map 0-100 to threshold 50-5
        //50 - (sensitivity * 45 / 100)
        motionThreshold = stepRange(sensitivity, 5, 50)

        
        // Also adjust min blob size based on sensitivity
        // 0 -> 256 pixels, 100 -> 16 pixels
        //64 - (sensitivity * 60 / 100)
        minBlobSize = stepRange(sensitivity, 4, 64)

        
        // Adjust background learning rate
        // Higher sensitivity = slower learning (don't absorb slow movement too fast)
        // 0 -> 0.1, 100 -> 0.01
        //0.15f - (sensitivity * 0.13f / 100)
        alpha = stepRange(sensitivity, 0.1f, 0.2f)

    }

    suspend fun processFrame(luma: IntArray, frameWidth: Int, frameHeight: Int, rotation: Int = 0) {
        // 1. Resize/Downsample for performance if needed
        // For simplicity, we assume the input is already low resolution or we process it as is
        // but given the requirements, we'll implement a fast downsampling if it's too big
        
        val workLuma: IntArray
        val workW: Int
        val workH: Int
        
        if (frameWidth > detectionWidth || frameHeight > detectionHeight) {
            workW = detectionWidth
            workH = detectionHeight
            workLuma = downsample(luma, frameWidth, frameHeight, workW, workH)
        } else {
            workW = frameWidth
            workH = frameHeight
            workLuma = luma
        }

        // 2. Initialize or Update Background Model
        if (backgroundModel == null || backgroundModel!!.size != workLuma.size) {
            backgroundModel = workLuma.copyOf()
            return
        }

        val bg = backgroundModel!!
        val diffMap = BooleanArray(workLuma.size)
        var motionCount = 0

        for (i in workLuma.indices) {
            val currentVal = workLuma[i]
            val bgVal = bg[i]
            val diff = abs(currentVal - bgVal)

            if (diff > motionThreshold) {
                diffMap[i] = true
                motionCount++
            }

            // Update background slowly
            bg[i] = ((1f - alpha) * bgVal + alpha * currentVal).toInt()
        }

        // 3. Group detected pixels into bounding boxes (Blob Detection)
        val boxes = if (motionCount > 0) {
            findBoundingBoxes(diffMap, workW, workH)
        } else {
            emptyList()
        }

        val intensity = motionCount.toFloat() / workLuma.size
        val hasMotion = boxes.isNotEmpty()

        _motionFlow.emit(MotionResult(hasMotion, boxes, intensity, workW, workH, rotation))
    }

    private fun downsample(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val dst = IntArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val srcX = (x * xRatio).toInt()
                val srcY = (y * yRatio).toInt()
                dst[y * dstW + x] = src[srcY * srcW + srcX]
            }
        }
        return dst
    }

    private fun findBoundingBoxes(diffMap: BooleanArray, w: Int, h: Int): List<RectF> {
        val visited = BooleanArray(diffMap.size)
        val boxes = mutableListOf<RectF>()
        
        // Use a grid-based approach for speed on low-power hardware
        val gridSize = 8
        val gridW = w / gridSize
        val gridH = h / gridSize
        
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val idx = (gy * gridSize) * w + (gx * gridSize)
                if (diffMap[idx] && !visited[idx]) {
                    // Start a "blob" search
                    val (rect, count) = floodFillRect(diffMap, visited, gx * gridSize, gy * gridSize, w, h)
                    if (count >= minBlobSize) {
                         // Convert to normalized coordinates 0..1
                         boxes.add(RectF(
                             rect.left / w,
                             rect.top / h,
                             rect.right / w,
                             rect.bottom / h
                         ))
                    }
                }
            }
        }
        
        // Merge overlapping or very close boxes to simplify
        return mergeBoxes(boxes)
    }

    private fun floodFillRect(diffMap: BooleanArray, visited: BooleanArray, startX: Int, startY: Int, w: Int, h: Int): Pair<RectF, Int> {
        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY
        
        val stack = mutableListOf<Pair<Int, Int>>()
        stack.add(startX to startY)
        visited[startY * w + startX] = true
        
        var count = 0
        val maxPixels = 500 // Limit search size for performance

        while (stack.isNotEmpty() && count < maxPixels) {
            val (cx, cy) = stack.removeAt(stack.size - 1)
            count++
            
            if (cx < minX) minX = cx
            if (cx > maxX) maxX = cx
            if (cy < minY) minY = cy
            if (cy > maxY) maxY = cy
            
            // Check neighbors in a sparse way for performance
            val step = 4
            val neighbors = listOf(cx to cy - step, cx to cy + step, cx - step to cy, cx + step to cy)
            
            for ((nx, ny) in neighbors) {
                if (nx in 0 until w && ny in 0 until h) {
                    val nIdx = ny * w + nx
                    if (diffMap[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        stack.add(nx to ny)
                    }
                }
            }
        }
        
        return RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()) to count
    }

    private fun mergeBoxes(boxes: List<RectF>): List<RectF> {
        if (boxes.size < 2) return boxes
        
        val result = mutableListOf<RectF>()
        val skip = BooleanArray(boxes.size)
        
        for (i in boxes.indices) {
            if (skip[i]) continue
            val current = RectF(boxes[i])
            
            for (j in i + 1 until boxes.size) {
                if (skip[j]) continue
                
                // If boxes are close or overlap, merge
                val buffer = 0.05f
                val expanded = RectF(current.left - buffer, current.top - buffer, current.right + buffer, current.bottom + buffer)
                
                if (expanded.intersect(boxes[j])) {
                    current.union(boxes[j])
                    skip[j] = true
                }
            }
            result.add(current)
        }
        return result
    }
}
