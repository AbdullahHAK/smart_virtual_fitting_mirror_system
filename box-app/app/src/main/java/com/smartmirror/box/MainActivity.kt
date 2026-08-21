package com.smartmirror.box

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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

        val shirtBitmap = loadAssetBitmap("products/shirt_placeholder_front.png")
        val pantsBitmap = loadAssetBitmap("products/pants_placeholder_front.png")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (hasCameraPermission) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraPreviewScreen(
                                analysisExecutor = analysisExecutor,
                                onFrame = { imageProxy -> poseLandmarkerHelper.detectAsync(imageProxy) }
                            )
                            PoseOverlay(latestPose, shirtBitmap, pantsBitmap)
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

    private fun loadAssetBitmap(path: String): Bitmap =
        assets.open(path).use { BitmapFactory.decodeStream(it) }

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
                @Suppress("DEPRECATION")
                val rotation = (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.rotation

                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
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

// BlazePose landmark indices used to approximate the torso/leg region.
private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24
private const val LEFT_ANKLE = 27
private const val RIGHT_ANKLE = 28

// Maps the bitmap's 4 corners onto an arbitrary quad (topLeft, topRight,
// bottomRight, bottomLeft) via a projective transform, so a rectangular product
// image warps onto the body's tracked pose instead of being drawn as a flat block.
private fun DrawScope.drawWarpedGarment(
    bitmap: Bitmap,
    topLeft: Offset,
    topRight: Offset,
    bottomRight: Offset,
    bottomLeft: Offset
) {
    val src = floatArrayOf(
        0f, 0f,
        bitmap.width.toFloat(), 0f,
        bitmap.width.toFloat(), bitmap.height.toFloat(),
        0f, bitmap.height.toFloat()
    )
    val dst = floatArrayOf(
        topLeft.x, topLeft.y,
        topRight.x, topRight.y,
        bottomRight.x, bottomRight.y,
        bottomLeft.x, bottomLeft.y
    )
    val matrix = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
    drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(bitmap, matrix, null) }
}

@Composable
private fun PoseOverlay(result: PoseLandmarkerResult?, shirtBitmap: Bitmap, pantsBitmap: Bitmap) {
    if (result == null || result.landmarks().isEmpty()) return
    val landmarks = result.landmarks()[0]
    if (landmarks.size <= RIGHT_ANKLE) return

    fun point(index: Int, width: Float, height: Float) =
        Offset(landmarks[index].x() * width, landmarks[index].y() * height)

    // Pushes a left/right pair apart around their midpoint. The garment images
    // already include their own sleeve/leg width, so this only needs to scale
    // the quad to roughly match body size, not fake the garment shape itself.
    fun widen(left: Offset, right: Offset, factor: Float): Pair<Offset, Offset> {
        val midX = (left.x + right.x) / 2f
        return Offset(midX + (left.x - midX) * factor, left.y) to
            Offset(midX + (right.x - midX) * factor, right.y)
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

        val (leftShoulder, rightShoulder) = widen(liftedLeftShoulder, liftedRightShoulder, 1.5f)
        val (leftHip, rightHip) = widen(rawLeftHip, rawRightHip, 1.55f)

        drawWarpedGarment(shirtBitmap, leftShoulder, rightShoulder, rightHip, leftHip)

        val (leftAnkle, rightAnkle) = widen(
            point(LEFT_ANKLE, size.width, size.height),
            point(RIGHT_ANKLE, size.width, size.height),
            1.4f
        )
        drawWarpedGarment(pantsBitmap, leftHip, rightHip, rightAnkle, leftAnkle)
    }
}
