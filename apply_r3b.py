p = "app/src/main/java/com/example/aimeshvision/ui/OverlayView.kt"
s = open(p, encoding="utf-8").read()

# Fix leftover lastSeen references from the R2 patch
s = s.replace("inst.lastSeen = now", "inst.lastFrame = frameCounter")
s = s.replace("assignments[det]!!.lastSeen = now", "assignments[det]!!.lastFrame = frameCounter")

# R4 draw block: locate the CURRENT onDraw mask section verbatim
import re
m = re.search(r"(            val mask = det\.maskBitmap\n)(.*?)(\n            if \(showBoxes\) \{)", s, re.S)
assert m, "draw section not found"

old_draw = """                val maskRect = RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)
                val path = if (showSmoothOutline && item.dots != null) {
                    buildSmoothPath(item.dots, maskRect)
                } else null

                if (path != null && isSaneClipPath(path, maskRect)) {"""
if old_draw in s:
    new_draw = """                val maskRect = RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)

                // One outline per chain - multi-blob masks stay fully outlined
                // (reviewer #2: longest-chain-only dropped blobs).
                val paths = if (showSmoothOutline && item.chains.isNotEmpty()) {
                    item.chains.mapNotNull { chain ->
                        if (chain.size >= 3) buildSmoothPath(chain, maskRect) else null
                    }
                } else emptyList()

                if (paths.isNotEmpty()) {"""
    s = s.replace(old_draw, new_draw)
    # rewrite the body that used single path
    old_body = """                    // 1. Mesh strictly inside the line: tinted mask clipped to
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
                }"""
    new_body = """                    // 1. Mesh inside the line: clip to the union of all
                    //    chain paths (sanity-checked). With no qualifying
                    //    path, draw the mask unclipped - the mesh must never
                    //    be erased.
                    val save = canvas.save()
                    for (p in paths) {
                        if (isSaneClipPath(p, maskRect)) canvas.clipPath(p)
                    }
                    drawTintedMask(canvas, mask, maskRect, classColor)
                    canvas.restoreToCount(save)

                    // 2. Stroke every chain's boundary.
                    linePaint.color = DetectionStyle.vibrantFor(classColor)
                    linePaint.alpha = 255
                    for (p in paths) canvas.drawPath(p, linePaint)
                }"""
    assert old_body in s, "body not found"
    s = s.replace(old_body, new_body)
else:
    raise SystemExit("draw block pattern not found - inspect manually")

# R3: open path unless the chain genuinely closes
old_bp = """        val mids = FloatArray(n * 2)
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
new_bp = """        // Closure test: last dot within ~2 grid steps of the first - closing
        // an OPEN chain would draw a straight cut across the mesh (reviewer #3).
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
assert old_bp in s, "buildSmoothPath body not found"
s = s.replace(old_bp, new_bp)

# R12: clear labelCache in resetSmoothing
old_rs = """    fun resetSmoothing() {
        instances.clear()
        prepared = emptyList()
        invalidate()
    }"""
new_rs = """    fun resetSmoothing() {
        instances.clear()
        labelCache.clear()
        prepared = emptyList()
        invalidate()
    }"""
assert old_rs in s, "resetSmoothing not found"
s = s.replace(old_rs, new_rs)

open(p, "w", encoding="utf-8", newline="\n").write(s)
print("R3+R4+R12 ok")
