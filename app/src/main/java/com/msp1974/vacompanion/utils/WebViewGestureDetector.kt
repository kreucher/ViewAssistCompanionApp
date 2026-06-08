package com.msp1974.vacompanion.utils

import android.view.MotionEvent
import timber.log.Timber
import kotlin.math.abs

class WebViewGestureDetector {
    private var startX = 0f
    private var startY = 0f
    private var pivotX = 0f
    private var pivotY = 0f
    private var maxPointers = 0
    private var firstLegDirection: Direction? = null
    private var isLGestureDetected = false

    private val SWIPE_THRESHOLD = 150f
    private val L_LEG_THRESHOLD = 100f

    enum class Direction {
        LEFT, RIGHT, UP, DOWN
    }

    fun onTouchEvent(event: MotionEvent) {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        if (pointerCount > maxPointers) {
            maxPointers = pointerCount
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                maxPointers = 1
                firstLegDirection = null
                isLGestureDetected = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (maxPointers == 1 && !isLGestureDetected) {
                    val dx = event.x - startX
                    val dy = event.y - startY

                    if (firstLegDirection == null) {
                        if (abs(dx) > L_LEG_THRESHOLD && abs(dx) > abs(dy) * 2) {
                            firstLegDirection = if (dx > 0) Direction.RIGHT else Direction.LEFT
                            pivotX = event.x
                            pivotY = event.y
                        } else if (abs(dy) > L_LEG_THRESHOLD && abs(dy) > abs(dx) * 2) {
                            firstLegDirection = if (dy > 0) Direction.DOWN else Direction.UP
                            pivotX = event.x
                            pivotY = event.y
                        }
                    } else {
                        val dpx = event.x - pivotX
                        val dpy = event.y - pivotY
                        
                        when (firstLegDirection) {
                            Direction.LEFT, Direction.RIGHT -> {
                                if (abs(dpy) > L_LEG_THRESHOLD && abs(dpy) > abs(dpx) * 2) {
                                    isLGestureDetected = true
                                    val secondDir = if (dpy > 0) "DOWN" else "UP"
                                    Timber.i("L-Shaped Gesture detected: $firstLegDirection then $secondDir")
                                }
                            }
                            Direction.UP, Direction.DOWN -> {
                                if (abs(dpx) > L_LEG_THRESHOLD && abs(dpx) > abs(dpy) * 2) {
                                    isLGestureDetected = true
                                    val secondDir = if (dpx > 0) "RIGHT" else "LEFT"
                                    Timber.i("L-Shaped Gesture detected: $firstLegDirection then $secondDir")
                                }
                            }
                            null -> {}
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isLGestureDetected) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (maxPointers == 1) {
                        detectSwipe(dx, dy, 1)
                    } else if (maxPointers in 2..3) {
                        detectSwipe(dx, dy, maxPointers)
                    }
                }
                maxPointers = 0
            }
        }
    }

    private fun detectSwipe(dx: Float, dy: Float, pointers: Int) {
        if (abs(dx) > SWIPE_THRESHOLD || abs(dy) > SWIPE_THRESHOLD) {
            val direction = if (abs(dx) > abs(dy)) {
                if (dx > 0) "RIGHT" else "LEFT"
            } else {
                if (dy > 0) "DOWN" else "UP"
            }
            val prefix = if (pointers == 1) "Single finger" else "$pointers fingers"
            Timber.i("$prefix swipe $direction")
        }
    }
}
