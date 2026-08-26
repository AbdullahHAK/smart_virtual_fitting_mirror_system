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

    // Hip/ankle center+width for the pants mesh, smoothed independently of
    // the generic per-index landmark smoother above — see
    // SymmetricPairSmoother for why left/right needs different handling here.
    private var hipCenterWidth by mutableStateOf<Triple<Float, Float, Float>?>(null)
    private var ankleCenterWidth by mutableStateOf<Triple<Float, Float, Float>?>(null)
    private val hipSmoother = SymmetricPairSmoother()
    private val ankleSmoother = SymmetricPairSmoother()
    private var showShirt by mutableStateOf(true)
    private var showPants by mutableStateOf(true)
    private var currentShirtProductId by mutableStateOf<Int?>(null)
    private var currentPantsProductId by mutableStateOf<Int?>(null)
    private var currentShirtBitmap by mutableStateOf<Bitmap?>(null)
    private var currentPantsBitmap by mutableStateOf<Bitmap?>(null)
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
                    hipCenterWidth = hipSmoother.update(
                        landmarks[LEFT_HIP].x(), landmarks[LEFT_HIP].y(),
                        landmarks[RIGHT_HIP].x(), landmarks[RIGHT_HIP].y()
                    )
                    ankleCenterWidth = ankleSmoother.update(
                        landmarks[LEFT_ANKLE].x(), landmarks[LEFT_ANKLE].y(),
                        landmarks[RIGHT_ANKLE].x(), landmarks[RIGHT_ANKLE].y()
                    )
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

        val productDb = ProductDbHelper(this)

        val initialProducts = productDb.getAllProducts()
        val initialShirt = initialProducts.firstOrNull { it.category == "shirt" }
        val initialPants = initialProducts.firstOrNull { it.category == "pants" }
        currentShirtProductId = initialShirt?.id
        currentPantsProductId = initialPants?.id
        currentShirtBitmap = initialShirt?.let { loadProductBitmap(it.asset) }
        currentPantsBitmap = initialPants?.let { loadProductBitmap(it.asset) }

        commandServer = CommandServer(
            port = 8080,
            getProducts = { productDb.getAllProducts() },
            getAssetBytes = { fileName -> loadProductBytes(fileName) },
            onAddProduct = { name, category, colorKey, imageTempPath ->
                if (imageTempPath == null) {
                    -1L
                } else {
                    val destFile = java.io.File(productImageDir(), "${java.util.UUID.randomUUID()}.png")
                    saveProcessedProductImage(imageTempPath, destFile)
                    productDb.insertProduct(name, category, colorKey, destFile.name)
                }
            },
            onUpdateProduct = { id, name, category, colorKey, imageTempPath ->
                val existing = productDb.getProduct(id)
                if (existing == null) {
                    false
                } else {
                    val assetFileName = if (imageTempPath != null) {
                        val destFile = java.io.File(productImageDir(), "${java.util.UUID.randomUUID()}.png")
                        saveProcessedProductImage(imageTempPath, destFile)
                        destFile.name
                    } else {
                        existing.asset
                    }
                    productDb.updateProduct(id, name, category, colorKey, assetFileName)
                    if (currentShirtProductId == id) currentShirtBitmap = loadProductBitmap(assetFileName)
                    if (currentPantsProductId == id) currentPantsBitmap = loadProductBitmap(assetFileName)
                    true
                }
            },
            onDeleteProduct = { id ->
                val existing = productDb.getProduct(id)
                if (existing == null) {
                    false
                } else {
                    productDb.deleteProduct(id)
                    // Only ever delete files under the mutable products/ dir we
                    // created ourselves — bundled assets/ images aren't touched.
                    val f = java.io.File(productImageDir(), existing.asset)
                    if (f.exists()) f.delete()
                    true
                }
            }
        ) { shirt, pants, shirtProductId, pantsProductId ->
            shirt?.let { showShirt = it }
            pants?.let { showPants = it }
            shirtProductId?.let { id ->
                productDb.getProduct(id)?.let { p ->
                    if (p.category == "shirt") {
                        currentShirtProductId = id
                        currentShirtBitmap = loadProductBitmap(p.asset)
                    }
                }
            }
            pantsProductId?.let { id ->
                productDb.getProduct(id)?.let { p ->
                    if (p.category == "pants") {
                        currentPantsProductId = id
                        currentPantsBitmap = loadProductBitmap(p.asset)
                    }
                }
            }
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
                            PoseOverlay(
                                smoothedLandmarks,
                                hipCenterWidth,
                                ankleCenterWidth,
                                currentShirtBitmap,
                                currentPantsBitmap,
                                showShirt,
                                showPants
                            )
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

    // Uploaded/admin-edited product images live here (mutable, unlike assets/
    // which is baked into the APK and read-only at runtime).
    private fun productImageDir(): java.io.File = java.io.File(filesDir, "products").apply { mkdirs() }

    private fun loadProductBytes(fileName: String): ByteArray? {
        val file = java.io.File(productImageDir(), fileName)
        if (file.exists()) return file.readBytes()
        return try {
            assets.open("products/$fileName").use { it.readBytes() }
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun loadProductBitmap(fileName: String): Bitmap? {
        val bytes = loadProductBytes(fileName) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // Admin-uploaded photos are rarely pre-processed transparent PNGs — staff
    // send a phone photo (often re-encoded to opaque JPEG by whatever app they
    // used to transfer it), so background removal runs automatically here
    // rather than trusting the upload to already have alpha. Downscaled first
    // since a raw camera photo is far larger than the overlay needs and would
    // make the flood-fill (and every future /productImage fetch) needlessly slow.
    private fun saveProcessedProductImage(sourcePath: String, destFile: java.io.File) {
        val original = BitmapFactory.decodeFile(sourcePath)
        val downscaled = downscaleIfNeeded(original)
        val processed = BackgroundRemover.removeBackground(downscaled)
        java.io.FileOutputStream(destFile).use { out ->
            processed.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun downscaleIfNeeded(bitmap: Bitmap, maxDimension: Int = 1024): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

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

                // Capped to 640x480 for the analysis stream ONLY — this is the
                // frame MediaPipe/the rotation step processes, invisible to the
                // customer, and pose detection doesn't benefit from anything
                // larger (the model resizes its input internally regardless).
                // Preview above is untouched, so the on-screen mirror image
                // stays full quality. Uncapped, CameraX was feeding analysis a
                // much larger default frame, which is the main cost behind the
                // low measured Pose FPS.
                val analysisResolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(640, 480),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolutionSelector)
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

// Warps the bitmap onto an (N columns) x (M rows) mesh via drawBitmapMesh —
// each row is an independent list of points, so garments needing more than a
// left/right pair per row (e.g. pants: outer-left / crotch / outer-right) can
// use the same function as simpler two-column garments.
private fun DrawScope.drawMeshWarpedGarment(bitmap: Bitmap, rows: List<List<Offset>>) {
    val cols = rows.first().size
    val verts = FloatArray(rows.size * cols * 2)
    var i = 0
    for (row in rows) {
        for (pt in row) {
            verts[i++] = pt.x
            verts[i++] = pt.y
        }
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmapMesh(bitmap, cols - 1, rows.size - 1, verts, 0, null, 0, null)
    }
}

@Composable
private fun PoseOverlay(
    smoothedLandmarks: Pair<FloatArray, FloatArray>?,
    hipCenterWidth: Triple<Float, Float, Float>?,
    ankleCenterWidth: Triple<Float, Float, Float>?,
    shirtBitmap: Bitmap?,
    pantsBitmap: Bitmap?,
    showShirt: Boolean,
    showPants: Boolean
) {
    if (smoothedLandmarks == null) return
    val (lmX, lmY) = smoothedLandmarks
    if (lmX.size <= RIGHT_ANKLE) return

    fun point(index: Int, width: Float, height: Float) =
        Offset(lmX[index] * width, lmY[index] * height)

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

        // Places a row at an EXPLICIT absolute width, centered on that row's own
        // landmark-anchored midpoint (still using each side's own Y, so it keeps
        // following body tilt). Deriving width as a ratio of chestWidth directly
        // — rather than a multiplier applied to the naturally-shrinking
        // shoulder->hip base line — guarantees the waist/hem-to-chest
        // relationship holds regardless of any individual's shoulder/hip ratio,
        // instead of two independently-tuned numbers happening to combine into
        // the right result for whichever body was last tested.
        fun bandWithWidth(t: Float, width: Float): List<Offset> {
            val l = leftAt(t)
            val r = rightAt(t)
            val midX = (l.x + r.x) / 2f
            return listOf(Offset(midX - width / 2f, l.y), Offset(midX + width / 2f, r.y))
        }

        val shoulderWidthPx = kotlin.math.abs(rawRightShoulder.x - rawLeftShoulder.x)
        val chestWidth = shoulderWidthPx * 2.3f // chest is the widest reference point

        // Small, consistent per-side ease so the shirt sits just outside the
        // torso boundary rather than tracing it exactly — closes the remaining
        // side gap on wider/muscular bodies without another width multiplier.
        val garmentEaseMargin = chestWidth * 0.04f // per side; adds 2x this to total width

        val shoulderBand = bandWithWidth(0f, chestWidth * 0.90f + 2f * garmentEaseMargin)
        val chestBand = bandWithWidth(0.30f, chestWidth + 2f * garmentEaseMargin)
        val waistBand = bandWithWidth(0.65f, chestWidth * 0.98f + 2f * garmentEaseMargin) // near-chest width, minimal taper
        val hemBand = bandWithWidth(1.0f, chestWidth + 2f * garmentEaseMargin)

        // Pants width/center come from hipCenterWidth/ankleCenterWidth (smoothed
        // via SymmetricPairSmoother), NOT from the individually-smoothed raw
        // left/right hip and ankle points. A prior version used raw per-side
        // points directly and collapsed to a single line whenever the person
        // faced the camera straight-on: a symmetric frontal pose gives MediaPipe
        // the least visual asymmetry to tell left from right, so it can flicker
        // which physical point is "left hip" vs "right hip" frame to frame —
        // smoothing left.x and right.x separately then averages the flicker
        // toward the centerline, dragging both edges inward. Center and width
        // are invariant to that swap, so building the mesh from those instead
        // is immune to it. See SymmetricPairSmoother for the full reasoning.
        if (showPants && pantsBitmap != null && hipCenterWidth != null && ankleCenterWidth != null) {
            val (hipCX, hipCY, hipWNorm) = hipCenterWidth
            val (ankleCX, ankleCY, _) = ankleCenterWidth

            val hipCenterPx = Offset(hipCX * size.width, hipCY * size.height)
            val ankleCenterPx = Offset(ankleCX * size.width, ankleCY * size.height)
            val hipWidthPx = hipWNorm * size.width

            fun legRow(centerPx: Offset, rowWidthPx: Float, easePx: Float): List<Offset> {
                val outerHalf = rowWidthPx / 2f + easePx
                return listOf(
                    Offset(centerPx.x - outerHalf, centerPx.y),
                    centerPx,
                    Offset(centerPx.x + outerHalf, centerPx.y)
                )
            }

            // Waist width is taken directly from the shirt's own hem band
            // (already computed above) instead of estimated independently from
            // hip landmarks — guarantees the two line up by construction, not
            // by two separately-tuned numbers happening to agree. Knee/ankle
            // stay on their existing (unchanged) width, so the leg still tapers
            // naturally from this wider waist rather than being widened
            // uniformly along its whole length.
            val hemWidthPx = kotlin.math.abs(hemBand[1].x - hemBand[0].x)
            val hemCenterX = (hemBand[0].x + hemBand[1].x) / 2f

            // The hip row's anchor is nudged a little above the hip landmark —
            // not a general lengthening, just enough that the pants' top edge
            // tucks behind the shirt hem (drawn after, below) instead of risking
            // a visible gap or the pants rendering on top of the shirt.
            val pantsTopNudge = hipWidthPx * 0.12f
            val hipRowAnchor = Offset(hemCenterX, hipCenterPx.y - pantsTopNudge)
            // hemWidthPx is derived from chestWidth (the shirt's widest point,
            // shoulder-based), not a direct measurement of the rendered hem
            // edge, and a t-shirt hem's curved corners sit inside that
            // measurement too — a 90% margin still read as visibly wider than
            // the shirt in practice, so cutting further based on the actual
            // observed gap rather than nudging the same 10% again.
            val waistWidthPx = hemWidthPx * 0.72f
            val hipRow = legRow(hipRowAnchor, waistWidthPx, 0f)
            // Ankle keeps the same ~89% taper ratio to the waist as before,
            // just scaled down with the smaller waist so the leg shape itself
            // doesn't change, only the overall size.
            val ankleRow = legRow(ankleCenterPx, waistWidthPx * 0.89f, 0f)
            // Knee row interpolated from hip->ankle rather than driven by the raw
            // knee landmark: knees are hard for MediaPipe to place confidently
            // when fully covered by pants (no visible knee to detect), and a
            // stuck bad reading would pinch this row toward a point. Same
            // "reactive landmark tracking is fragile" lesson as the shirt's
            // reverted elbow-anchoring.
            val kneeRow = hipRow.zip(ankleRow) { h, a -> lerp(h, a, 0.5f) }

            drawMeshWarpedGarment(pantsBitmap, listOf(hipRow, kneeRow, ankleRow))
        }

        // Drawn after (on top of) pants: shirt hem visually occludes the top of
        // the pants' waistband, matching real layering (body -> pants -> shirt)
        // instead of risking the pants rendering over the shirt.
        if (showShirt && shirtBitmap != null) {
            drawMeshWarpedGarment(shirtBitmap, listOf(shoulderBand, chestBand, waistBand, hemBand))
        }
    }
}
