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
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24
private const val LEFT_ANKLE = 27
private const val RIGHT_ANKLE = 28

// Warps the bitmap onto a 2-column x N-row mesh via drawBitmapMesh — each row
// is an independent (left, right) pair, so different bands of the garment
// (e.g. shoulder/chest/waist/hem) can each have their own width instead of
// one rigid quad or a single interpolated taper.
private fun DrawScope.drawMeshWarpedGarment(bitmap: Bitmap, rows: List<Pair<Offset, Offset>>) {
    val verts = FloatArray(rows.size * 4)
    rows.forEachIndexed { i, (left, right) ->
        verts[i * 4] = left.x
        verts[i * 4 + 1] = left.y
        verts[i * 4 + 2] = right.x
        verts[i * 4 + 3] = right.y
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmapMesh(bitmap, 1, rows.size - 1, verts, 0, null, 0, null)
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

    fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

    Canvas(modifier = Modifier.fillMaxSize()) {
        val rawLeftShoulder = point(LEFT_SHOULDER, size.width, size.height)
        val rawRightShoulder = point(RIGHT_SHOULDER, size.width, size.height)
        val rawLeftHip = point(LEFT_HIP, size.width, size.height)
        val rawRightHip = point(RIGHT_HIP, size.width, size.height)

        val shoulderCenter = Offset((rawLeftShoulder.x + rawRightShoulder.x) / 2f, (rawLeftShoulder.y + rawRightShoulder.y) / 2f)
        val hipCenter = Offset((rawLeftHip.x + rawRightHip.x) / 2f, (rawLeftHip.y + rawRightHip.y) / 2f)
        val torsoHeight = hipCenter.y - shoulderCenter.y

        // Raise the shoulder line from the joint (roughly armpit height) up toward
        // collar height, so the shirt doesn't start at chest level.
        val collarLift = torsoHeight * 0.18f
        val liftedLeftShoulder = rawLeftShoulder.copy(y = rawLeftShoulder.y - collarLift)
        val liftedRightShoulder = rawRightShoulder.copy(y = rawRightShoulder.y - collarLift)

        // Each side's own line from shoulder to hip — anchored directly to real
        // landmarks, so it follows the body's actual lean/tilt instead of a
        // reconstructed center point (which drifted independently per row and
        // made the shirt look crooked whenever hip-x wasn't exactly under
        // shoulder-x, which is the normal case, not an edge case).
        fun leftAt(t: Float) = lerp(liftedLeftShoulder, rawLeftHip, t)
        fun rightAt(t: Float) = lerp(liftedRightShoulder, rawRightHip, t)

        // Independent widen-factor per row instead of one constant: chest pushes
        // out further than the shoulder line, hem compensates for hip landmarks
        // sitting anatomically closer together than shoulder landmarks do.
        fun band(t: Float, widenFactor: Float) = widen(leftAt(t), rightAt(t), widenFactor)

        // Single ease parameter — the widen-factor must INCREASE from chest
        // through hem (not dip at the waist) because the underlying shoulder->hip
        // line it's applied to is already narrowing on its own at those rows
        // (hips sit anatomically closer together than shoulders). A dipping
        // factor on top of an already-shrinking base is what compounded into
        // the hourglass pinch. Monotonically non-decreasing after the chest
        // avoids that regardless of a person's exact shoulder/hip ratio.
        val garmentEase = 2.0f
        val shoulderBand = band(0f, garmentEase * 0.85f)
        val chestBand = band(0.30f, garmentEase * 1.15f)
        val waistBand = band(0.65f, garmentEase * 1.25f)
        val hemBand = band(1.0f, garmentEase * 1.35f)

        if (showShirt) {
            drawMeshWarpedGarment(shirtBitmap, listOf(shoulderBand, chestBand, waistBand, hemBand))
        }

        val (leftHip, rightHip) = widen(rawLeftHip, rawRightHip, 2.1f)
        val (leftAnkle, rightAnkle) = widen(
            point(LEFT_ANKLE, size.width, size.height),
            point(RIGHT_ANKLE, size.width, size.height),
            1.4f
        )
        if (showPants) {
            drawMeshWarpedGarment(
                pantsBitmap,
                listOf(
                    leftHip to rightHip,
                    lerp(leftHip, leftAnkle, 0.5f) to lerp(rightHip, rightAnkle, 0.5f),
                    leftAnkle to rightAnkle
                )
            )
        }
    }
}
