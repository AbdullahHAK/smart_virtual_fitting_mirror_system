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
private const val LEFT_ANKLE = 27
private const val RIGHT_ANKLE = 28

@Composable
private fun PoseOverlay(result: PoseLandmarkerResult?) {
    if (result == null || result.landmarks().isEmpty()) return
    val landmarks = result.landmarks()[0]
    if (landmarks.size <= RIGHT_ANKLE) return

    fun point(index: Int, width: Float, height: Float) =
        Offset(landmarks[index].x() * width, landmarks[index].y() * height)

    // Pushes a left/right pair apart around their midpoint so the garment covers
    // real body width instead of just the skeleton's joint-to-joint centerline.
    fun widen(left: Offset, right: Offset, factor: Float): Pair<Offset, Offset> {
        val midX = (left.x + right.x) / 2f
        return Offset(midX + (left.x - midX) * factor, left.y) to
            Offset(midX + (right.x - midX) * factor, right.y)
    }

    // A fixed-width band around the hip->ankle segment, so each leg reads as its
    // own tube instead of one trapezoid spanning both legs (which erases the gap
    // between them).
    fun legBand(hip: Offset, ankle: Offset, halfWidth: Float): Path {
        val dx = ankle.x - hip.x
        val dy = ankle.y - hip.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: 1f
        val perpX = -dy / len * halfWidth
        val perpY = dx / len * halfWidth
        return Path().apply {
            moveTo(hip.x + perpX, hip.y + perpY)
            lineTo(ankle.x + perpX, ankle.y + perpY)
            lineTo(ankle.x - perpX, ankle.y - perpY)
            lineTo(hip.x - perpX, hip.y - perpY)
            close()
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val rawLeftShoulder = point(LEFT_SHOULDER, size.width, size.height)
        val rawRightShoulder = point(RIGHT_SHOULDER, size.width, size.height)
        val rawLeftHip = point(LEFT_HIP, size.width, size.height)
        val rawRightHip = point(RIGHT_HIP, size.width, size.height)

        // Raise the shoulder line from the joint (roughly armpit height) up toward
        // collar height, so the shirt doesn't start at chest level.
        val torsoHeight = ((rawLeftHip.y + rawRightHip.y) / 2f) - ((rawLeftShoulder.y + rawRightShoulder.y) / 2f)
        val collarLift = torsoHeight * 0.18f
        val liftedLeftShoulder = rawLeftShoulder.copy(y = rawLeftShoulder.y - collarLift)
        val liftedRightShoulder = rawRightShoulder.copy(y = rawRightShoulder.y - collarLift)

        val (leftShoulder, rightShoulder) = widen(liftedLeftShoulder, liftedRightShoulder, 1.3f)
        val (leftHip, rightHip) = widen(rawLeftHip, rawRightHip, 1.35f)

        val shirtPath = Path().apply {
            moveTo(leftShoulder.x, leftShoulder.y)
            lineTo(rightShoulder.x, rightShoulder.y)
            lineTo(rightHip.x, rightHip.y)
            lineTo(leftHip.x, leftHip.y)
            close()
        }
        drawPath(shirtPath, color = Color(0x99FF5722))

        val leftAnkle = point(LEFT_ANKLE, size.width, size.height)
        val rightAnkle = point(RIGHT_ANKLE, size.width, size.height)
        val shoulderWidth = kotlin.math.abs(rawRightShoulder.x - rawLeftShoulder.x)
        val legHalfWidth = shoulderWidth * 0.16f

        drawPath(legBand(rawLeftHip, leftAnkle, legHalfWidth), color = Color(0x993F51B5))
        drawPath(legBand(rawRightHip, rightAnkle, legHalfWidth), color = Color(0x993F51B5))

        for (landmark in landmarks) {
            drawCircle(
                color = Color.Green,
                radius = 5f,
                center = Offset(landmark.x() * size.width, landmark.y() * size.height)
            )
        }
    }
}
