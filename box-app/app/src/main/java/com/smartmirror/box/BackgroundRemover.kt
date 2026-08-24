package com.smartmirror.box

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque

// Automatic background removal for admin-uploaded product photos. No ML model
// here on purpose — a real segmentation model would need either a hefty
// on-device model or a cloud API, and the latter breaks this system's
// fully-offline requirement. Instead: flood-fill inward from the image border,
// removing pixels connected to the edge that are color-close to the border's
// own average color. This only works for photos shot against a plain,
// fairly uniform background (by design — staff are instructed to photograph
// garments against a background clearly different from the garment itself).
object BackgroundRemover {

    private const val COLOR_THRESHOLD = 40

    // The mesh-warp overlay stretches a bitmap's full width/height edge-to-edge
    // onto the shoulder-to-hem (or hip-to-ankle) target area on the body — it
    // has no idea where the garment actually sits within the source image. A
    // photo where the garment only fills part of the frame (shot too far away,
    // or off-center) would map that surrounding empty space onto the body too,
    // shrinking the garment inside its target area. Cropping to the garment's
    // own tight bounding box after background removal fixes this regardless of
    // how the original photo was framed.
    private const val CROP_MARGIN_RATIO = 0.03f

    fun removeBackground(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val bgColor = averageBorderColor(pixels, width, height)
        val visited = BooleanArray(width * height)
        val queue = ArrayDeque<Int>()

        fun index(x: Int, y: Int) = y * width + x

        fun enqueueIfBackground(x: Int, y: Int) {
            if (x < 0 || x >= width || y < 0 || y >= height) return
            val idx = index(x, y)
            if (visited[idx]) return
            if (colorDistance(pixels[idx], bgColor) <= COLOR_THRESHOLD) {
                visited[idx] = true
                queue.add(idx)
            }
        }

        for (x in 0 until width) {
            enqueueIfBackground(x, 0)
            enqueueIfBackground(x, height - 1)
        }
        for (y in 0 until height) {
            enqueueIfBackground(0, y)
            enqueueIfBackground(width - 1, y)
        }

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % width
            val y = idx / width
            pixels[idx] = pixels[idx] and 0x00FFFFFF // keep RGB, drop alpha to 0
            enqueueIfBackground(x - 1, y)
            enqueueIfBackground(x + 1, y)
            enqueueIfBackground(x, y - 1)
            enqueueIfBackground(x, y + 1)
        }

        // Tight bounding box of surviving (non-background) pixels.
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                if (!visited[rowBase + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            // Nothing survived (e.g. a photo of a blank/uniform surface with no
            // actual garment) — fail safe with the un-cropped, background-
            // stripped bitmap rather than crashing on an empty crop rect.
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            result.setPixels(pixels, 0, width, 0, 0, width, height)
            return result
        }

        val marginX = ((maxX - minX + 1) * CROP_MARGIN_RATIO).toInt()
        val marginY = ((maxY - minY + 1) * CROP_MARGIN_RATIO).toInt()
        val cropLeft = (minX - marginX).coerceAtLeast(0)
        val cropTop = (minY - marginY).coerceAtLeast(0)
        val cropRight = (maxX + marginX).coerceAtMost(width - 1)
        val cropBottom = (maxY + marginY).coerceAtMost(height - 1)
        val cropWidth = cropRight - cropLeft + 1
        val cropHeight = cropBottom - cropTop + 1

        val croppedPixels = IntArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) {
            System.arraycopy(pixels, (cropTop + y) * width + cropLeft, croppedPixels, y * cropWidth, cropWidth)
        }

        val result = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        result.setPixels(croppedPixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
        return result
    }

    private fun averageBorderColor(pixels: IntArray, width: Int, height: Int): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0L
        fun accumulate(x: Int, y: Int) {
            val c = pixels[y * width + x]
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
            count++
        }
        for (x in 0 until width) {
            accumulate(x, 0)
            accumulate(x, height - 1)
        }
        for (y in 0 until height) {
            accumulate(0, y)
            accumulate(width - 1, y)
        }
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun colorDistance(c1: Int, c2: Int): Int {
        val dr = Color.red(c1) - Color.red(c2)
        val dg = Color.green(c1) - Color.green(c2)
        val db = Color.blue(c1) - Color.blue(c2)
        return maxOf(kotlin.math.abs(dr), kotlin.math.abs(dg), kotlin.math.abs(db))
    }
}
