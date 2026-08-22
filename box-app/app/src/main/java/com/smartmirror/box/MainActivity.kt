package com.smartmirror.box

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.net.NetworkInterface
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var smoothedLandmarks by mutableStateOf<Pair<FloatArray, FloatArray>?>(null)
    private var poseFps by mutableStateOf(0f)
    private val landmarkSmoother = LandmarkSmoother()
    private var lastPoseResultAtMs = 0L
    private var showShirt by mutableStateOf(true)
    private var showPants by mutableStateOf(true)
    private var shirtColor by mutableStateOf("blue")
    private lateinit var commandServer: CommandServer

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
            onResult = { result, _, _ ->
                val landmarks = result.landmarks().firstOrNull()
                if (landmarks != null) {
                    smoothedLandmarks = landmarkSmoother.update(landmarks)
                }
                val now = System.currentTimeMillis()
                if (lastPoseResultAtMs != 0L) {
                    val deltaMs = (now - lastPoseResultAtMs).coerceAtLeast(1)
                    poseFps = 1000f / deltaMs
                }
                lastPoseResultAtMs = now
            },
            onError = { /* surfaced later once we have a status UI */ }
        )

        val shirtBitmaps = mapOf(
            "blue" to loadAssetBitmap("products/shirt_blue.png"),
            "red" to loadAssetBitmap("products/shirt_red.png"),
            "green" to loadAssetBitmap("products/shirt_green.png")
        )
        val pantsBitmap = loadAssetBitmap("products/pants_placeholder_front.png")

        val productDb = ProductDbHelper(this)

        commandServer = CommandServer(
            port = 8080,
            getProducts = { productDb.getAllProducts() }
        ) { shirt, pants, color ->
            shirt?.let { showShirt = it }
            pants?.let { showPants = it }
            color?.let { if (shirtBitmaps.containsKey(it)) shirtColor = it }
        }
        try {
            commandServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: java.io.IOException) {
            // Port likely still held by a just-killed previous instance; the app
            // still works for standalone testing, it just won't take commands
            // from the tablet until the port frees up (or the phone restarts).
            android.util.Log.e("MainActivity", "CommandServer failed to start on port 8080", e)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (hasCameraPermission) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraPreviewScreen(
                                analysisExecutor = analysisExecutor,
                                onFrame = { imageProxy -> poseLandmarkerHelper.detectAsync(imageProxy) }
                            )
                            PoseOverlay(smoothedLandmarks, shirtBitmaps.getValue(shirtColor), pantsBitmap, showShirt, showPants)
                            Text(
                                text = "Box IP: ${localIpAddress() ?: "unknown"}:8080  |  Pose FPS: ${"%.0f".format(poseFps)}",
                                color = Color.White,
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.foundation.layout.Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                            ) {
                                Text("Camera permission is required.")
                                androidx.compose.material3.Button(
                                    onClick = { requestPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                    modifier = Modifier.padding(top = 12.dp)
                                ) { Text("Grant permission") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadAssetBitmap(path: String): Bitmap =
        assets.open(path).use { BitmapFactory.decodeStream(it) }

    private fun localIpAddress(): String? =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress

    override fun onDestroy() {
        super.onDestroy()
        poseLandmarkerHelper.close()
        analysisExecutor.shutdown()
        commandServer.stop()
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

// BlazePose landmark indices used to approximate the torso/leg region.
private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_ELBOW = 13
private const val RIGHT_ELBOW = 14
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24
private const val LEFT_KNEE = 25
private const val RIGHT_KNEE = 26
private const val LEFT_ANKLE = 27
private const val RIGHT_ANKLE = 28

// Warps the bitmap onto a 2-column x 3-row mesh (top/mid/bottom-left and
// -right) instead of a single rigid quad, via drawBitmapMesh.
private fun DrawScope.drawMeshWarpedGarment(
    bitmap: Bitmap,
    topLeft: Offset,
    topRight: Offset,
    midLeft: Offset,
    midRight: Offset,
    bottomLeft: Offset,
    bottomRight: Offset
) {
    // Row-major, top-to-bottom / left-to-right, matching drawBitmapMesh's
    // implicit uniform source grid for meshWidth=1, meshHeight=2.
    val verts = floatArrayOf(
        topLeft.x, topLeft.y, topRight.x, topRight.y,
        midLeft.x, midLeft.y, midRight.x, midRight.y,
        bottomLeft.x, bottomLeft.y, bottomRight.x, bottomRight.y
    )
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmapMesh(bitmap, 1, 2, verts, 0, null, 0, null)
    }
}

@Composable
private fun PoseOverlay(
    smoothedLandmarks: Pair<FloatArray, FloatArray>?,
    shirtBitmap: Bitmap,
    pantsBitmap: Bitmap,
    showShirt: Boolean,
    showPants: Boolean
) {
    if (smoothedLandmarks == null) return
    val (lmX, lmY) = smoothedLandmarks
    if (lmX.size <= RIGHT_ANKLE) return

    fun point(index: Int, width: Float, height: Float) =
        Offset(lmX[index] * width, lmY[index] * height)

    // Pushes a left/right pair apart around their midpoint. The garment images
    // already include their own sleeve/leg width, so this only needs to scale
    // the quad to roughly match body size, not fake the garment shape itself.
    fun widen(left: Offset, right: Offset, factor: Float): Pair<Offset, Offset> {
        val midX = (left.x + right.x) / 2f
        return Offset(midX + (left.x - midX) * factor, left.y) to
            Offset(midX + (right.x - midX) * factor, right.y)
    }

    // Straight-line interpolation between a top and bottom anchor, used for the
    // mesh's middle row. Deliberately NOT tied to elbow/knee position: an
    // earlier version anchored the shirt's mid-row to the elbows so sleeves
    // would "bend" with the arms, but that made the whole shirt collapse
    // inward whenever arms crossed in front of the body (very common pose) —
    // a shirt's actual width at chest height shouldn't change just because
    // someone crosses their arms. Trading that reactivity for reliability.
    fun lerp(top: Offset, bottom: Offset, t: Float) =
        Offset(top.x + (bottom.x - top.x) * t, top.y + (bottom.y - top.y) * t)

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
        // MediaPipe's hip landmarks sit near the pelvis, anatomically closer
        // together than the shoulder landmarks — using a similar widen factor
        // for both under-states hip/waist width relative to the chest, which
        // is what was producing the tapered/triangular look. Compensating.
        val (leftHip, rightHip) = widen(rawLeftHip, rawRightHip, 2.1f)

        // Previous hem-drop (0.15) overshot and reached toward the crotch;
        // pulling it back to a more normal tee length.
        val hemDrop = torsoHeight * 0.06f
        val leftHem = leftHip.copy(y = leftHip.y + hemDrop)
        val rightHem = rightHip.copy(y = rightHip.y + hemDrop)

        if (showShirt) {
            drawMeshWarpedGarment(
                shirtBitmap,
                topLeft = leftShoulder, topRight = rightShoulder,
                midLeft = lerp(leftShoulder, leftHem, 0.45f), midRight = lerp(rightShoulder, rightHem, 0.45f),
                bottomLeft = leftHem, bottomRight = rightHem
            )
        }

        val (leftAnkle, rightAnkle) = widen(
            point(LEFT_ANKLE, size.width, size.height),
            point(RIGHT_ANKLE, size.width, size.height),
            1.4f
        )
        if (showPants) {
            drawMeshWarpedGarment(
                pantsBitmap,
                topLeft = leftHip, topRight = rightHip,
                midLeft = lerp(leftHip, leftAnkle, 0.5f), midRight = lerp(rightHip, rightAnkle, 0.5f),
                bottomLeft = leftAnkle, bottomRight = rightAnkle
            )
        }
    }
}
