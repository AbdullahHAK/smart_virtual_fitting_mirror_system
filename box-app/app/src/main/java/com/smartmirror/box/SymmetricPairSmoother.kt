package com.smartmirror.box

// Smooths a left/right landmark pair (e.g. hips, ankles) via their CENTER and
// WIDTH rather than their individual X coordinates.
//
// MediaPipe can momentarily flip which physical point it calls "left" vs
// "right" for a body part — most likely in a symmetric frontal pose, where
// there's the least visual asymmetry available to disambiguate the two
// sides. Center and width are invariant to that swap (swapping which point
// is "left" changes neither the midpoint nor the distance apart), so
// smoothing them directly is immune to the flicker. Smoothing left.x and
// right.x separately is not: an oscillating identity swap gets averaged, and
// both converge toward the same central value — collapsing the whole
// garment toward a single line, which is exactly the "pants become one line
// when facing the camera straight on" symptom this fixes.
class SymmetricPairSmoother(private val alpha: Float = 0.3f) {
    private var centerX: Float? = null
    private var centerY: Float? = null
    private var width: Float? = null

    fun update(aX: Float, aY: Float, bX: Float, bY: Float): Triple<Float, Float, Float> {
        val rawCenterX = (aX + bX) / 2f
        val rawCenterY = (aY + bY) / 2f
        val rawWidth = kotlin.math.abs(bX - aX)

        val cx = centerX?.let { it + (rawCenterX - it) * alpha } ?: rawCenterX
        val cy = centerY?.let { it + (rawCenterY - it) * alpha } ?: rawCenterY
        val w = width?.let { it + (rawWidth - it) * alpha } ?: rawWidth

        centerX = cx
        centerY = cy
        width = w
        return Triple(cx, cy, w)
    }
}
