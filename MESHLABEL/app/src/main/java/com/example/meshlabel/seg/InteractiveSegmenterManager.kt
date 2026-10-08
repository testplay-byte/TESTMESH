package com.example.meshlabel.seg

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.example.meshlabel.model.Pt
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin, thread-safe wrapper around MediaPipe's InteractiveSegmenter
 * ("Magic Touch": scribble strokes -> confidence mask).
 *
 * API contract (verified against google-ai-edge/gallery ScrapbookViewModel):
 *  - create once from the bundled asset `interactive_segmentation.task`;
 *  - `setImage` ONCE per bitmap (re-set only when the bitmap identity
 *    changes) - strokes then re-segment the same image cheaply;
 *  - `segment(List<Stroke>)` is a SYNCHRONOUS JNI call - everything here
 *    runs on a private single-thread executor, results are posted back to
 *    the main thread;
 *  - strokes carry polarity per-stroke (POSITIVE = add, NEGATIVE = remove)
 *    with NORMALIZED [0,1] keypoints; one combined segment() call;
 *  - the result is a full-image-size float confidence mask, thresholded
 *    at 0.5 (the gallery's threshold) into a BooleanArray.
 *
 * Stale-result discipline: every call gets a monotonically increasing
 * requestId; listeners MUST drop results whose id is older than the last
 * they accepted (the executor delivers in order, but a user may have
 * changed the image/strokes between request and delivery).
 */
class InteractiveSegmenterManager(context: Context) {

    companion object {
        /** Bundled model (MediaPipe interactive_segmenter_v2 / magic_touch). */
        const val MODEL_ASSET = "interactive_segmentation.task"

        /** Confidence threshold (matches google-ai-edge/gallery). */
        const val CONFIDENCE_THRESHOLD = 0.5f
    }

    /** One scribble: image-pixel points + polarity. */
    data class Spec(val points: List<Pt>, val positive: Boolean)

    interface Listener {
        fun onSegmentResult(requestId: Int, mask: BooleanArray, width: Int, height: Int)
        fun onSegmentError(requestId: Int, message: String)
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestSeq = AtomicInteger(0)

    @Volatile private var segmenter: InteractiveSegmenter? = null
    @Volatile private var initError: String? = null

    /** Executor-confined: the bitmap the segmenter's image slot belongs to. */
    private var setImageFor: Bitmap? = null

    /**
     * Submits a segmentation request. Returns its requestId (pass it back
     * when filtering callbacks). With no strokes the call is a no-op - the
     * listener is NOT invoked (caller clears its preview itself).
     */
    fun segment(image: Bitmap, strokes: List<Spec>, listener: Listener): Int {
        val id = requestSeq.incrementAndGet()
        if (strokes.isEmpty()) return id
        executor.execute {
            try {
                val seg = ensureSegmenter()
                    ?: throw IllegalStateException(initError ?: "Segmentation model unavailable")
                if (setImageFor !== image) {
                    seg.setImage(BitmapImageBuilder(image).build())
                    setImageFor = image
                }
                val strokeList = strokes.map { spec ->
                    Stroke.builder()
                        .setBrushMode(
                            if (spec.positive) Stroke.BrushMode.POSITIVE
                            else Stroke.BrushMode.NEGATIVE
                        )
                        .setPoints(
                            spec.points.map {
                                NormalizedKeypoint.create(
                                    it.x / image.width,
                                    it.y / image.height,
                                )
                            }
                        )
                        .setCompleted(true)
                        .build()
                }
                val result = seg.segment(strokeList)
                val buffer = ByteBufferExtractor.extract(result)
                buffer.order(ByteOrder.nativeOrder())
                val floats = FloatArray(image.width * image.height)
                buffer.asFloatBuffer().get(floats)
                val mask = BooleanArray(floats.size) { i -> floats[i] > CONFIDENCE_THRESHOLD }
                mainHandler.post {
                    listener.onSegmentResult(id, mask, image.width, image.height)
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    listener.onSegmentError(id, t.message ?: "Segmentation failed")
                }
            }
        }
        return id
    }

    /** Lazy model creation (executor thread). Returns null + sets initError. */
    private fun ensureSegmenter(): InteractiveSegmenter? {
        segmenter?.let { return it }
        if (initError != null) return null
        return try {
            val options = InteractiveSegmenterOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        // Delegate defaults to CPU (stable for the int8
                        // magic_touch model; GPU offers no win for a
                        // one-shot per-stroke call).
                        .build()
                )
                .build()
            InteractiveSegmenter.createFromOptions(appContext, options)
                .also { segmenter = it }
        } catch (t: Throwable) {
            initError = "Could not load $MODEL_ASSET: ${t.message}"
            null
        }
    }

    /** Releases the native handle. Idempotent; safe from onDestroy. */
    fun close() {
        executor.execute {
            try {
                segmenter?.close()
            } catch (_: Throwable) {
            }
            segmenter = null
            setImageFor = null
        }
        executor.shutdown()
    }
}
