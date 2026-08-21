package com.smartmirror.box

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseLandmarkerHelper(
    context: Context,
    onResult: (result: PoseLandmarkerResult, imageWidth: Int, imageHeight: Int) -> Unit,
    onError: (String) -> Unit
) {
    private val poseLandmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_PATH)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setResultListener { result, input -> onResult(result, input.width, input.height) }
            .setErrorListener { error -> onError(error.message ?: "Pose detection error") }
            .build()
    )

    fun detectAsync(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
        val mpImage = BitmapImageBuilder(rotated).build()
        poseLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis())
        imageProxy.close()
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun close() = poseLandmarker.close()

    private companion object {
        const val MODEL_PATH = "pose_landmarker_lite.task"
    }
}
