package com.smartmirror.box

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

// Exponential smoothing across frames so the garment tracks the body smoothly
// instead of jittering with MediaPipe's raw per-frame landmark noise. Also
// holds the last good position for a landmark when its visibility score drops
// below threshold, rather than snapping the garment to a low-confidence
// (often wrong) position.
class LandmarkSmoother(private val alpha: Float = 0.4f, private val minVisibility: Float = 0.4f) {
    private var smoothedX: FloatArray? = null
    private var smoothedY: FloatArray? = null

    fun update(landmarks: List<NormalizedLandmark>): Pair<FloatArray, FloatArray> {
        val sx = smoothedX ?: FloatArray(landmarks.size) { landmarks[it].x() }.also { smoothedX = it }
        val sy = smoothedY ?: FloatArray(landmarks.size) { landmarks[it].y() }.also { smoothedY = it }

        for (i in landmarks.indices) {
            val lm = landmarks[i]
            val visibility = lm.visibility()
            val isVisible = !visibility.isPresent || visibility.get() >= minVisibility
            if (isVisible) {
                sx[i] += (lm.x() - sx[i]) * alpha
                sy[i] += (lm.y() - sy[i]) * alpha
            }
            // else: hold the previous smoothed value for this landmark.
        }
        return sx to sy
    }
}
