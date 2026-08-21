package com.smartmirror.box

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var latestPose by mutableStateOf<PoseLandmarkerResult?>(null)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        poseLandmarkerHelper = PoseLandmarkerHelper(
            context = this,
            onResult = { result, _, _ -> latestPose = result },
            onError = { /* surfaced later once we have a status UI */ }
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (hasCameraPermission) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraPreviewScreen(
                                analysisExecutor = analysisExecutor,
                                onFrame = { imageProxy -> poseLandmarkerHelper.detectAsync(imageProxy) }
                            )
                            PoseOverlay(latestPose)
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Camera permission is required.")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        poseLandmarkerHelper.close()
        analysisExecutor.shutdown()
    }
}

@Composable
private fun CameraPreviewScreen(
    analysisExecutor: java.util.concurrent.ExecutorService,
    onFrame: (androidx.camera.core.ImageProxy) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) } }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

// BlazePose landmark indices used to approximate the torso region.
private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24

@Composable
private fun PoseOverlay(result: PoseLandmarkerResult?) {
    if (result == null || result.landmarks().isEmpty()) return
    val landmarks = result.landmarks()[0]
    if (landmarks.size <= RIGHT_HIP) return

    fun point(index: Int, width: Float, height: Float) =
        Offset(landmarks[index].x() * width, landmarks[index].y() * height)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val leftShoulder = point(LEFT_SHOULDER, size.width, size.height)
        val rightShoulder = point(RIGHT_SHOULDER, size.width, size.height)
        val rightHip = point(RIGHT_HIP, size.width, size.height)
        val leftHip = point(LEFT_HIP, size.width, size.height)

        val shirtPath = Path().apply {
            moveTo(leftShoulder.x, leftShoulder.y)
            lineTo(rightShoulder.x, rightShoulder.y)
            lineTo(rightHip.x, rightHip.y)
            lineTo(leftHip.x, leftHip.y)
            close()
        }
        drawPath(shirtPath, color = Color(0x99FF5722))

        for (landmark in landmarks) {
            drawCircle(
                color = Color.Green,
                radius = 5f,
                center = Offset(landmark.x() * size.width, landmark.y() * size.height)
            )
        }
    }
}
