p = "app/src/main/java/com/example/aimeshvision/ui/OverlayView.kt"
s = open(p, encoding="utf-8").read()

# R3: open path unless the chain genuinely closes
old = """    /**
     * Builds the smooth closed path THROUGH the chained dots: on-curve
     * midpoints + dot control points (quadratic bezier) - each dot is
     * represented exactly, corners stay sharp where the dots say so.
     */
    private fun buildSmoothPath(
        dots: List<Pair<Float, Float>>, maskRect: RectF,
    ): Path {
        val n = dots.size
        if (n < 3) return Path()

        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            pts[i * 2] = maskRect.left + dots[i].first * maskRect.width()
            pts[i * 2 + 1] = maskRect.top + dots[i].second * maskRect.height()
        }

        val mids = FloatArray(n * 2)
        for (i in 0 until n) {
            val j = (i + 1) % n
            mids[i * 2] = (pts[i * 2] + pts[j * 2]) / 2f
            mids[i * 2 + 1] = (pts[i * 2 + 1] + pts[j * 2 + 1]) / 2f
        }

        val path = Path()
        path.moveTo(mids[0], mids[1])
        for (i in 0 until n) {
            val j = (i + 1) % n
            path.quadTo(
                pts[j * 2], pts[j * 2 + 1],      // control = real border dot
                mids[j * 2], mids[j * 2 + 1],    // anchor   = midpoint
            )
        }
        path.close()
        return path
    }"""
new = """    /**
     * Builds the smooth path THROUGH the chained dots: on-curve midpoints +
     * dot control points (quadratic bezier). The path is CLOSED only when the
     * chain genuinely loops (last dot within reach of the first) - closing an
     * open chain would draw a straight cut across the mesh (reviewer #3).
     */
    private fun buildSmoothPath(
        dots: List<Pair<Float, Float>>, maskRect: RectF,
    ): Path {
        val n = dots.size
        if (n < 3) return Path()

        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            pts[i * 2] = maskRect.left + dots[i].first * maskRect.width()
            pts[i * 2 + 1] = maskRect.top + dots[i].second * maskRect.height()
        }

        // Closure test: last dot within ~2 grid steps of the first.
        val gapX = (dots[0].first - dots[n - 1].first) * maskRect.width()
        val gapY = (dots[0].second - dots[n - 1].second) * maskRect.height()
        val closes = (gapX * gapX + gapY * gapY) < 64f

        val mids = FloatArray(n * 2)
        val segs = if (closes) n else n - 1
        for (i in 0 until segs) {
            val j = (i + 1) % n
            mids[i * 2] = (pts[i * 2] + pts[j * 2]) / 2f
            mids[i * 2 + 1] = (pts[i * 2 + 1] + pts[j * 2 + 1]) / 2f
        }

        val path = Path()
        path.moveTo(mids[0], mids[1])
        for (i in 0 until segs) {
            val j = (i + 1) % n
            path.quadTo(
                pts[j * 2], pts[j * 2 + 1],      // control = real border dot
                mids[i * 2], mids[i * 2 + 1],    // anchor   = midpoint
            )
        }
        if (closes) path.close()
        return path
    }"""
assert old in s, "R3 anchor not found"
s = s.replace(old, new)

# R4: render+clip EVERY chain
old = """            val mask = det.maskBitmap
            if (mask != null) {
                val maskRect = RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)
                val path = if (showSmoothOutline && item.dots != null) {
                    buildSmoothPath(item.dots, maskRect)
                } else null

                if (path != null && isSaneClipPath(path, maskRect)) {
                    // 1. Mesh strictly inside the line: tinted mask clipped to
                    //    the dot-chained boundary curve. Sanity-checked: a
                    //    degenerate path (blended instances, tiny blob) must
                    //    never erase the mesh - it just skips the clip.
                    val save = canvas.save()
                    canvas.clipPath(path)
                    drawTintedMask(canvas, mask, maskRect, classColor)
                    canvas.restoreToCount(save)

                    // 2. Vibrant, opaque class-color stroke ON the boundary -
                    //    half on the mesh, half outside it.
                    linePaint.color = DetectionStyle.vibrantFor(classColor)
                    linePaint.alpha = 255
                    canvas.drawPath(path, linePaint)
                } else {
                    drawTintedMask(canvas, mask, maskRect, classColor)
                }
            }"""
new = """            val mask = det.maskBitmap
            if (mask != null) {
                val maskRect = RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)

                // One outline per chain - multi-blob masks stay fully outlined
                // (reviewer #2: longest-chain-only dropped blobs).
                val paths = if (showSmoothOutline && item.chains.isNotEmpty()) {
                    item.chains.mapNotNull { chain ->
                        if (chain.size >= 3) buildSmoothPath(chain, maskRect) else null
                    }
                } else emptyList()

                // 1. Mesh inside the line: clip to the union of all chain
                //    paths (sanity-checked). With no qualifying path, draw the
                //    mask unclipped - the mesh must never be erased.
                val save = canvas.save()
                for (p in paths) {
                    if (isSaneClipPath(p, maskRect)) canvas.clipPath(p)
                }
                drawTintedMask(canvas, mask, maskRect, classColor)
                canvas.restoreToCount(save)

                // 2. Stroke every chain's boundary.
                if (paths.isNotEmpty()) {
                    linePaint.color = DetectionStyle.vibrantFor(classColor)
                    linePaint.alpha = 255
                    for (p in paths) canvas.drawPath(p, linePaint)
                }
            }"""
assert old in s, "R4 draw block not found"
s = s.replace(old, new)

# R12: clear labelCache in resetSmoothing
old = """    /** Forgets temporal smoothing state (e.g. when the model changes). */
    fun resetSmoothing() {
        instances.clear()
        prepared = emptyList()
        invalidate()
    }"""
new = """    /** Forgets temporal smoothing state (e.g. when the model changes). */
    fun resetSmoothing() {
        instances.clear()
        labelCache.clear()
        prepared = emptyList()
        invalidate()
    }"""
assert old in s, "R12 anchor not found"
s = s.replace(old, new)

open(p, "w", encoding="utf-8", newline="\n").write(s)
print("R3+R4+R12 ok")
