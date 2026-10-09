package com.example.aimeshvision.inference

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * One detection produced by the model, in ORIGINAL image coordinates.
 *
 * @param boundingBox normalized box [0..1] relative to the source image
 * @param classId     model class index
 * @param confidence  final confidence (objectness x class prob for v5, class prob for v8)
 * @param label       human-readable class name (resolved from labels.txt)
 * @param maskCoefficients  YOLO-seg prototype coefficients (null for detect-only models)
 * @param maskBitmap  decoded segmentation mask (set after post-processing; null if none)
 */
data class Detection(
    val boundingBox: RectF,
    val classId: Int,
    val confidence: Float,
    val label: String,
    val maskCoefficients: FloatArray? = null,
    var maskBitmap: Bitmap? = null,
    /**
     * IMAGE-SPACE OUTLINE BAND: small bitmap (~128px) covering the same
     * source rect as [maskBitmap], whose alpha ramps bright immediately
     * OUTSIDE the visible mesh edge (decoded alongside the mask - see
     * YoloPostProcessor.smoothMaskAlpha). Render cache like maskBitmap;
     * excluded from equals/hashCode by the custom implementations below.
     */
    var outlineBitmap: Bitmap? = null,
    /**
     * Normalized rect [0..1] of the SOURCE IMAGE that [maskBitmap]
     * covers. Defaults cover the whole image - which is CORRECT for
     * full-frame decodes: parseOutput normalizes model coords over the
     * USEFUL (letterbox-free) region, so the decoded usable area IS the
     * entire source image (round 25: a "real proto window" stamp was
     * tried in round 24 and pinned the mask ~10% inward per side -
     * the squished-centered mesh / dead left+right zones report). The
     * split&detect refine path sets these to its crop window, so a crop
     * mask with its own local geometry maps to the right place too.
     * Not part of equals/hashCode: like maskBitmap, this is a render
     * cache; frame identity comes from box/class/confidence.
     */
    val maskLeft: Float = 0f,
    val maskTop: Float = 0f,
    val maskWidth: Float = 1f,
    val maskHeight: Float = 1f,
) {
    /** Mask coefficients participate in equality; bitmaps do not (mutable render cache). */
    override fun equals(other: Any?): Boolean = other is Detection &&
        other.boundingBox == boundingBox && other.classId == classId &&
        other.confidence == confidence && other.label == label &&
        other.maskCoefficients?.contentEquals(maskCoefficients) == true

    override fun hashCode(): Int =
        listOf(boundingBox, classId, confidence, label).hashCode()
}
