package com.testplaybyte.loom.data.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.ByteArrayOutputStream

/**
 * Renders a compiled demo scene to real image bytes.
 *
 * Demo scenes are vector art (like the prototype's SVG "photos"), but the
 * app writes them into the project folder as actual image files so the
 * folder is a genuine dataset: annotate → export → the dataset contains
 * its images. The scene metadata's file names are JPEGs (IMG_2041.jpg…),
 * so the default encode is JPEG (quality 92 — flat illustrations compress
 * cleanly and stay true to the metadata); PNG is available for callers
 * that need lossless output.
 *
 * The art is drawn with the same op lists the canvas displays, so what the
 * user annotated is exactly what lands in the folder.
 */
object SceneImage {

    fun renderJpeg(scene: CompiledScene, quality: Int = 92): ByteArray =
        render(scene, Bitmap.CompressFormat.JPEG, quality)

    fun renderPng(scene: CompiledScene): ByteArray =
        render(scene, Bitmap.CompressFormat.PNG, 100)

    private fun render(scene: CompiledScene, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(SceneLibrary.WORLD_W, SceneLibrary.WORLD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        scene.drawAndroid(canvas)
        val out = ByteArrayOutputStream(96 * 1024)
        bitmap.compress(format, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
