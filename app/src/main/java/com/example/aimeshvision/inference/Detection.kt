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
    var maskBitmap: Bitmap? = null
) {
    /** Mask coefficients participate in equality; bitmaps do not (mutable render cache). */
    override fun equals(other: Any?): Boolean = other is Detection &&
        other.boundingBox == boundingBox && other.classId == classId &&
        other.confidence == confidence && other.label == label &&
        other.maskCoefficients?.contentEquals(maskCoefficients) == true

    override fun hashCode(): Int =
        listOf(boundingBox, classId, confidence, label).hashCode()
}
