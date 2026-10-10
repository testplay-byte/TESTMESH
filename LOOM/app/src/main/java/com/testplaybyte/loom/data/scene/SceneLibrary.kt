package com.testplaybyte.loom.data.scene

/**
 * SceneLibrary — the six demo "photos" of the demo dataset.
 *
 * 1:1 port of the prototype's `components/scene-art.tsx` (flat SVG
 * illustrations, 800×600 world). The op lists below follow the JSX element
 * ORDER exactly (painter's algorithm: background first, the target object
 * last), and every geometry value is copied verbatim — the authored anchor
 * rings in [SceneTarget] stay aligned with the art because both come from
 * the same source coordinates.
 *
 * JSX loops (pegboard holes, books, screw strips, notebook lines) are
 * expanded programmatically with the same arithmetic the source used,
 * which keeps the values exact and reviewable.
 */
object SceneLibrary {

    const val WORLD_W = 800
    const val WORLD_H = 600

    /** Compiled scenes in demo order (kitchen … desk). */
    val scenes: List<CompiledScene> by lazy {
        listOf(
            CompiledScene(kitchen),
            CompiledScene(street),
            CompiledScene(workbench),
            CompiledScene(shelf),
            CompiledScene(park),
            CompiledScene(desk),
        )
    }

    /** Scene lookup with the prototype's fallback (unknown id → first). */
    fun byId(id: String): CompiledScene = scenes.firstOrNull { it.id == id } ?: scenes[0]

    // hex helper: 0xFFRRGGBB
    private fun hex(rgb: Long): Int = (0xFF000000L or rgb).toInt()

    // ── 1. Kitchen counter — target: the apple ────────────────────────────

    private val kitchen = SceneDef(
        id = "kitchen",
        title = "Kitchen counter",
        file = "IMG_2041.jpg",
        tone = hex(0xDEC5A4),
        target = SceneTarget(
            name = "Apple",
            anchors = listOf(
                140f to 282f, 158f to 250f, 186f to 234f, 212f to 244f,
                222f to 272f, 210f to 300f, 180f to 312f, 152f to 302f,
            ),
            suggest = listOf(172f to 270f, 190f to 288f, 158f to 292f, 196f to 258f),
            suggestNeg = listOf(236f to 282f, 140f to 340f),
        ),
        ops = buildList {
            add(Op.RectOp(0f, 0f, 800f, 600f, color = hex(0xE8D5BC)))
            add(Op.RectOp(0f, 0f, 800f, 330f, color = hex(0xDEC5A4)))
            add(Op.RectOp(0f, 306f, 800f, 30f, color = hex(0xD3B58D), alpha = 0.7f))
            add(Op.RectOp(0f, 330f, 800f, 270f, color = hex(0xB98A5E)))
            add(Op.RectOp(0f, 330f, 800f, 16f, color = hex(0xA97A50)))
            add(Op.RectOp(0f, 342f, 800f, 8f, color = hex(0x8F6440), alpha = 0.55f))
            for (x in listOf(90f, 300f, 510f, 720f)) {
                add(Op.RectOp(x, 352f, 4f, 240f, color = hex(0xA97A50), alpha = 0.5f))
            }
            // window light
            add(Op.RectOp(80f, 60f, 180f, 200f, rx = 10f, color = hex(0xF6E8CF), alpha = 0.85f))
            add(Op.RectOp(96f, 76f, 148f, 168f, rx = 6f, color = hex(0xF9EFD9)))
            // kettle
            add(Op.EllipseOp(620f, 332f, 120f, 14f, color = hex(0x5E4426), alpha = 0.28f))
            add(Op.PathOp("M540 330 q0 -130 80 -130 q80 0 80 130 Z", fill = hex(0x4E5A57)))
            add(Op.PathOp("M540 330 q0 -130 80 -130 q10 0 18 3 q-62 22 -62 127 Z", fill = hex(0x5E6B67)))
            add(Op.PathOp("M700 250 q42 8 38 56 l-16 -4 q6 -32 -28 -38 Z", fill = hex(0x4E5A57)))
            add(Op.RectOp(604f, 182f, 32f, 20f, rx = 6f, color = hex(0x3C4643)))
            // jar
            add(Op.EllipseOp(352f, 330f, 58f, 10f, color = hex(0x5E4426), alpha = 0.25f))
            add(Op.RectOp(304f, 236f, 96f, 94f, rx = 14f, color = hex(0xC9A176)))
            add(Op.RectOp(304f, 236f, 30f, 94f, rx = 14f, color = hex(0xD8B48B)))
            add(Op.RectOp(298f, 222f, 108f, 22f, rx = 9f, color = hex(0x8A6A45)))
            // fruit bowl + apple (target)
            add(Op.EllipseOp(200f, 368f, 140f, 20f, color = hex(0x5E4426), alpha = 0.3f))
            add(Op.PathOp("M92 300 q108 96 216 0 q-14 66 -108 66 q-94 0 -108 -66 Z", fill = hex(0x7B8F7C)))
            add(Op.PathOp("M92 300 q108 96 216 0 q-40 26 -108 26 q-68 0 -108 -26 Z", fill = hex(0x8FA38D)))
            add(Op.CircleOp(176f, 282f, 30f, hex(0xC96B4A)))
            add(Op.CircleOp(168f, 272f, 10f, hex(0xDE8562), alpha = 0.8f))
            add(Op.CircleOp(232f, 286f, 26f, hex(0xD9A44C)))
            add(Op.EllipseOp(200f, 252f, 34f, 14f, color = hex(0x97AB90)))
            add(
                Op.PathOp(
                    "M176 258 q2 -22 14 -30",
                    stroke = hex(0x6B4F33), strokeWidth = 6f, capRound = true,
                ),
            )
            add(Op.PathOp("M192 232 q18 -14 30 -8 q-10 16 -30 8", fill = hex(0x7B8F6A)))
        },
    )

    // ── 2. Street corner — target: the car ────────────────────────────────

    private val street = SceneDef(
        id = "street",
        title = "Street corner",
        file = "IMG_2077.jpg",
        tone = hex(0xEFC99B),
        target = SceneTarget(
            name = "Car",
            anchors = listOf(
                156f to 420f, 196f to 356f, 280f to 318f, 396f to 320f, 470f to 372f,
                512f to 436f, 490f to 492f, 396f to 506f, 250f to 506f, 176f to 484f,
            ),
            suggest = listOf(
                336f to 400f, 262f to 452f, 420f to 448f, 336f to 340f, 214f to 396f,
            ),
            suggestNeg = listOf(476f to 210f, 134f to 396f, 560f to 300f),
        ),
        ops = buildList {
            add(Op.RectOp(0f, 0f, 800f, 600f, color = hex(0xF2D9B8)))
            add(Op.RectOp(0f, 0f, 800f, 380f, color = hex(0xEFC99B)))
            add(Op.CircleOp(640f, 110f, 54f, hex(0xF9E3BE)))
            add(Op.CircleOp(640f, 110f, 78f, hex(0xF9E3BE), alpha = 0.35f))
            add(Op.RectOp(0f, 352f, 800f, 248f, color = hex(0x8E8577)))
            add(Op.RectOp(0f, 346f, 800f, 12f, color = hex(0xA29A8B)))
            add(Op.RectOp(0f, 470f, 800f, 8f, color = hex(0xE8DFC9), alpha = 0.75f))
            add(Op.RectOp(0f, 492f, 800f, 8f, color = hex(0xE8DFC9), alpha = 0.5f))
            // buildings
            add(Op.RectOp(30f, 140f, 180f, 212f, color = hex(0xC9A276)))
            for (x in listOf(46f, 96f, 146f)) {
                add(Op.RectOp(x, 160f, 34f, 40f, color = hex(0xEFE0C2)))
            }
            for (x in listOf(46f, 96f, 146f)) {
                add(Op.RectOp(x, 220f, 34f, 40f, color = hex(0xEFE0C2)))
            }
            add(Op.RectOp(600f, 90f, 170f, 262f, color = hex(0xB08A63)))
            for (x in listOf(620f, 678f)) {
                add(Op.RectOp(x, 116f, 40f, 52f, color = hex(0xE8D8B8)))
            }
            for (x in listOf(620f, 678f)) {
                add(Op.RectOp(x, 190f, 40f, 52f, color = hex(0xE8D8B8)))
            }
            // traffic light
            add(Op.RectOp(470f, 180f, 12f, 172f, color = hex(0x575046)))
            add(Op.RectOp(448f, 120f, 56f, 104f, rx = 12f, color = hex(0x575046)))
            add(Op.CircleOp(476f, 150f, 15f, hex(0xD95F4B)))
            add(Op.CircleOp(476f, 188f, 15f, hex(0xE0B154), alpha = 0.35f))
            add(Op.CircleOp(476f, 210f, 15f, hex(0x7FA565), alpha = 0.35f))
            // hydrant
            add(Op.RectOp(118f, 380f, 34f, 52f, rx = 9f, color = hex(0xB8483C)))
            add(Op.RectOp(110f, 372f, 50f, 16f, rx = 8f, color = hex(0xC25A4C)))
            add(Op.RectOp(112f, 430f, 46f, 12f, rx = 5f, color = hex(0x9E3E33)))
            // car (target)
            add(Op.EllipseOp(330f, 500f, 190f, 22f, color = hex(0x3E382E), alpha = 0.35f))
            add(
                Op.PathOp(
                    "M158 452 q10 -76 76 -82 l26 -34 q14 -18 44 -18 l66 0 q30 0 44 18 l28 36 " +
                        "q64 8 72 80 l0 34 q-2 16 -20 16 l-296 0 q-18 0 -20 -16 Z",
                    fill = hex(0xC05B3F),
                ),
            )
            add(Op.PathOp("M264 342 q12 -14 34 -14 l58 0 q22 0 34 14 l20 28 l-166 0 Z", fill = hex(0xE8DCC4)))
            add(Op.RectOp(158f, 440f, 356f, 34f, rx = 10f, color = hex(0xA84C34)))
            add(Op.CircleOp(238f, 486f, 30f, hex(0x3A342B)))
            add(Op.CircleOp(238f, 486f, 13f, hex(0xC9C0AE)))
            add(Op.CircleOp(436f, 486f, 30f, hex(0x3A342B)))
            add(Op.CircleOp(436f, 486f, 13f, hex(0xC9C0AE)))
            add(Op.RectOp(486f, 432f, 26f, 14f, rx = 6f, color = hex(0xF2DF9E)))
        },
    )

    // ── 3. The workbench — target: the drill ──────────────────────────────

    private val workbench = SceneDef(
        id = "workbench",
        title = "The workbench",
        file = "IMG_2130.jpg",
        tone = hex(0xCBB794),
        target = SceneTarget(
            name = "Drill",
            anchors = listOf(
                344f to 396f, 392f to 348f, 500f to 342f, 566f to 380f, 636f to 392f,
                668f to 416f, 630f to 436f, 560f to 452f, 448f to 508f, 386f to 498f,
                342f to 452f,
            ),
            suggest = listOf(
                442f to 414f, 520f to 408f, 386f to 428f, 586f to 414f, 430f to 470f,
            ),
            suggestNeg = listOf(662f to 196f, 186f to 436f, 540f to 320f),
        ),
        ops = buildList {
            add(Op.RectOp(0f, 0f, 800f, 600f, color = hex(0xD8C6A8)))
            add(Op.RectOp(0f, 0f, 800f, 340f, color = hex(0xCBB794)))
            // pegboard
            add(Op.RectOp(60f, 48f, 420f, 240f, rx = 10f, color = hex(0xB7A17B)))
            for (r in 0 until 7) {
                for (c in 0 until 12) {
                    add(Op.CircleOp(88f + c * 34f, 76f + r * 32f, 3.4f, hex(0xA08A64)))
                }
            }
            // hanging tools
            add(Op.RectOp(120f, 70f, 16f, 90f, rx = 6f, color = hex(0x8B6F4D)))
            add(Op.RectOp(112f, 62f, 32f, 18f, rx = 6f, color = hex(0x6E5638)))
            add(Op.RectOp(188f, 70f, 12f, 70f, rx = 5f, color = hex(0x77705F)))
            add(Op.RectOp(180f, 62f, 28f, 14f, rx = 5f, color = hex(0x5E5849)))
            add(Op.RectOp(250f, 70f, 18f, 104f, rx = 7f, color = hex(0x9A5A40)))
            add(Op.RectOp(244f, 62f, 30f, 16f, rx = 6f, color = hex(0x7C452F)))
            // bench top
            add(Op.RectOp(0f, 340f, 800f, 260f, color = hex(0x9C7A52)))
            add(Op.RectOp(0f, 340f, 800f, 20f, color = hex(0x8A6A45)))
            add(Op.RectOp(0f, 360f, 800f, 6f, color = hex(0x7A5C3B), alpha = 0.6f))
            // lamp
            add(Op.EllipseOp(662f, 352f, 110f, 16f, color = hex(0x4E3A22), alpha = 0.3f))
            add(Op.RectOp(648f, 180f, 14f, 164f, rx = 6f, color = hex(0x5C554A)))
            add(Op.PathOp("M600 186 q62 -60 124 0 Z", fill = hex(0x6E6557)))
            add(Op.CircleOp(662f, 196f, 16f, hex(0xF4D98B)))
            // screw tray
            add(Op.RectOp(112f, 404f, 150f, 64f, rx = 10f, color = hex(0xB0895A)))
            add(Op.RectOp(120f, 412f, 134f, 48f, rx = 7f, color = hex(0x97744A)))
            for (i in 0 until 6) {
                add(Op.RectOp(134f + i * 20f, 424f, 8f, 26f, rx = 3f, color = hex(0x71675A)))
            }
            // drill (target)
            add(Op.EllipseOp(480f, 478f, 150f, 20f, color = hex(0x4E3A22), alpha = 0.32f))
            add(Op.RectOp(352f, 382f, 196f, 76f, rx = 26f, color = hex(0xD9A13F)))
            add(Op.PathOp("M352 402 q0 -22 26 -22 l36 0 l0 96 l-36 0 q-26 0 -26 -22 Z", fill = hex(0xC08A2E)))
            add(Op.PathOp("M548 396 l64 8 l0 34 l-64 8 Z", fill = hex(0x8A6B2E)))
            add(Op.RectOp(608f, 404f, 52f, 10f, rx = 4f, color = hex(0x5E5748)))
            add(Op.RectOp(656f, 402f, 18f, 14f, rx = 3f, color = hex(0x71675A)))
            add(Op.RectOp(404f, 452f, 56f, 46f, rx = 14f, color = hex(0xB07E2C)))
            add(Op.RectOp(418f, 452f, 12f, 46f, color = hex(0x96691F), alpha = 0.6f))
            add(Op.RectOp(500f, 356f, 34f, 30f, rx = 10f, color = hex(0xC08A2E)))
            add(Op.CircleOp(382f, 418f, 12f, hex(0xF2D98B)))
        },
    )

    // ── 4. Reading shelf — target: the potted plant ───────────────────────

    private val shelf = SceneDef(
        id = "shelf",
        title = "Reading shelf",
        file = "IMG_2188.jpg",
        tone = hex(0xE2D3B7),
        target = SceneTarget(
            name = "Potted plant",
            anchors = listOf(
                322f to 300f, 318f to 246f, 372f to 130f, 430f to 118f,
                482f to 250f, 462f to 316f, 396f to 340f, 346f to 326f,
            ),
            suggest = listOf(
                398f to 262f, 366f to 296f, 428f to 296f, 398f to 190f, 398f to 320f,
            ),
            suggestNeg = listOf(540f to 300f, 262f to 250f, 620f to 320f),
        ),
        ops = buildList {
            add(Op.RectOp(0f, 0f, 800f, 600f, color = hex(0xE2D3B7)))
            add(Op.RectOp(0f, 0f, 800f, 600f, color = hex(0xE9DBBF), alpha = 0.5f))
            // shelf boards
            add(Op.RectOp(70f, 360f, 660f, 26f, rx = 6f, color = hex(0x9A774E)))
            add(Op.RectOp(70f, 382f, 660f, 10f, color = hex(0x7E603C), alpha = 0.7f))
            add(Op.RectOp(110f, 170f, 560f, 24f, rx = 6f, color = hex(0x9A774E)))
            add(Op.RectOp(110f, 190f, 560f, 9f, color = hex(0x7E603C), alpha = 0.7f))
            // books upper
            val upper = listOf(Triple(150f, 86f, 0xA85B41L), Triple(186f, 92f, 0x6F7F5EL), Triple(222f, 80f, 0xC2924BL), Triple(258f, 90f, 0x7B6A54L))
            for ((x, h, col) in upper) {
                add(Op.RectOp(x, 258f - h, 30f, h, rx = 5f, color = hex(col)))
                add(Op.RectOp(x + 6f, 268f - h, 18f, 7f, rx = 3f, color = hex(0xF2E8D2), alpha = 0.8f))
            }
            // box
            add(Op.RectOp(500f, 272f, 130f, 76f, rx = 8f, color = hex(0xB9986B)))
            add(Op.RectOp(500f, 272f, 130f, 18f, rx = 8f, color = hex(0xC9AA7D)))
            add(Op.RectOp(556f, 272f, 16f, 76f, color = hex(0xA5854F), alpha = 0.6f))
            // books lower
            val lower = listOf(Triple(560f, 96f, 0x8A6B4FL), Triple(594f, 104f, 0xB8763FL), Triple(628f, 92f, 0x647A62L))
            for ((x, h, col) in lower) {
                add(Op.RectOp(x, 356f - h, 28f, h, rx = 5f, color = hex(col)))
                add(Op.RectOp(x + 5f, 366f - h, 18f, 6f, rx = 3f, color = hex(0xF2E8D2), alpha = 0.8f))
            }
            // plant (target)
            add(Op.EllipseOp(400f, 362f, 86f, 12f, color = hex(0x6E5638), alpha = 0.3f))
            add(Op.PathOp("M352 282 q48 -18 96 0 l-10 74 q-38 12 -76 0 Z", fill = hex(0xB86A48)))
            add(Op.PathOp("M352 282 q48 -18 96 0 l-4 26 q-44 12 -88 0 Z", fill = hex(0xC97D58)))
            add(Op.RectOp(390f, 240f, 18f, 46f, rx = 7f, color = hex(0x964F35)))
            // leaves
            add(Op.PathOp("M398 244 q-64 -40 -92 -104 q78 6 100 92 Z", fill = hex(0x5F7F55)))
            add(Op.PathOp("M402 244 q64 -44 88 -110 q-80 8 -98 98 Z", fill = hex(0x6F9161)))
            add(Op.PathOp("M400 240 q-10 -84 34 -140 q26 66 -18 142 Z", fill = hex(0x7FA36D)))
            add(Op.PathOp("M398 246 q-56 -12 -104 8 q58 28 106 12 Z", fill = hex(0x55744E)))
            add(Op.PathOp("M402 246 q58 -16 108 4 q-60 30 -110 14 Z", fill = hex(0x658759)))
        },
    )

    // ── 5. Morning park — target: the dog ─────────────────────────────────

    private val park = SceneDef(
        id = "park",
        title = "Morning park",
        file = "IMG_2243.jpg",
        tone = hex(0xA9B072),
        target = SceneTarget(
            name = "Dog",
            anchors = listOf(
                196f to 448f, 230f to 396f, 330f to 380f, 396f to 368f, 448f to 390f,
                454f to 442f, 408f to 492f, 318f to 508f, 240f to 502f,
            ),
            suggest = listOf(
                300f to 440f, 250f to 470f, 372f to 466f, 404f to 416f, 336f to 414f,
            ),
            suggestNeg = listOf(556f to 416f, 640f to 190f, 420f to 520f),
        ),
        ops = buildList {
            add(Op.RectOp(0f, 0f, 800f, 600f, color = hex(0xEFD9AE)))
            add(Op.RectOp(0f, 0f, 800f, 360f, color = hex(0xF4DFB2)))
            add(Op.CircleOp(150f, 104f, 46f, hex(0xFAE9C2)))
            add(Op.CircleOp(150f, 104f, 70f, hex(0xFAE9C2), alpha = 0.4f))
            // tree
            add(Op.RectOp(628f, 240f, 26f, 130f, rx = 9f, color = hex(0x7E5C39)))
            add(Op.CircleOp(640f, 190f, 86f, hex(0x8CA367)))
            add(Op.CircleOp(590f, 230f, 52f, hex(0x7C945B)))
            add(Op.CircleOp(692f, 234f, 46f, hex(0x97AD72)))
            // ground + path
            add(Op.RectOp(0f, 360f, 800f, 240f, color = hex(0xA9B072)))
            add(Op.RectOp(0f, 352f, 800f, 14f, color = hex(0xB8BC7E)))
            add(Op.PathOp("M240 600 q40 -130 180 -176 l40 22 q-110 60 -140 154 Z", fill = hex(0xD9C294)))
            // bench
            add(Op.EllipseOp(560f, 470f, 120f, 14f, color = hex(0x5E5636), alpha = 0.3f))
            add(Op.RectOp(470f, 396f, 180f, 16f, rx = 6f, color = hex(0x9A774E)))
            add(Op.RectOp(474f, 428f, 172f, 12f, rx = 5f, color = hex(0x8A6A45)))
            add(Op.RectOp(482f, 440f, 14f, 52f, rx = 5f, color = hex(0x7E603C)))
            add(Op.RectOp(616f, 440f, 14f, 52f, rx = 5f, color = hex(0x7E603C)))
            add(Op.RectOp(470f, 352f, 180f, 12f, rx = 5f, color = hex(0x8A6A45)))
            add(Op.RectOp(482f, 364f, 14f, 34f, rx = 5f, color = hex(0x7E603C)))
            add(Op.RectOp(616f, 364f, 14f, 34f, rx = 5f, color = hex(0x7E603C)))
            // dog (target)
            add(Op.EllipseOp(290f, 512f, 130f, 16f, color = hex(0x5E5636), alpha = 0.3f))
            add(
                Op.PathOp(
                    "M198 470 q6 -60 62 -62 l96 -2 q52 2 58 56 l4 34 q2 18 -18 18 l-190 0 q-16 0 -14 -20 Z",
                    fill = hex(0xC69A63),
                ),
            )
            add(
                Op.PathOp(
                    "M198 470 q6 -60 62 -62 l30 -1 q-44 18 -48 63 l-2 42 l-30 0 q-16 0 -14 -20 Z",
                    fill = hex(0xB78A52),
                ),
            )
            add(Op.CircleOp(404f, 416f, 44f, hex(0xC69A63)))
            add(Op.PathOp("M382 380 q-24 -26 -12 -44 q20 2 26 34 Z", fill = hex(0xA87B46)))
            add(Op.PathOp("M424 376 q24 -26 14 -46 q-22 4 -28 38 Z", fill = hex(0xA87B46)))
            add(Op.EllipseOp(440f, 424f, 17f, 13f, color = hex(0x8A6034)))
            add(Op.CircleOp(394f, 408f, 6f, hex(0x3E3428)))
            add(Op.RectOp(240f, 498f, 18f, 42f, rx = 8f, color = hex(0xA87B46)))
            add(Op.RectOp(330f, 498f, 18f, 42f, rx = 8f, color = hex(0xA87B46)))
            add(Op.PathOp("M204 428 q-26 -18 -20 -44 q24 8 34 36 Z", fill = hex(0xB78A52)))
        },
    )

    // ── 6. Home desk — target: the laptop ─────────────────────────────────

    private val desk = SceneDef(
        id = "desk",
        title = "Home desk",
        file = "IMG_2305.jpg",
        tone = hex(0xA57C50),
        target = SceneTarget(
            name = "Laptop",
            anchors = listOf(
                306f to 380f, 330f to 330f, 452f to 320f, 580f to 332f, 602f to 388f,
                630f to 470f, 560f to 494f, 452f to 500f, 330f to 488f, 292f to 466f,
            ),
            suggest = listOf(
                452f to 400f, 380f to 402f, 524f to 402f, 452f to 352f, 452f to 476f,
            ),
            suggestNeg = listOf(614f to 300f, 230f to 428f, 200f to 120f),
        ),
        ops = buildList {
            add(Op.RectOp(0f, 0f, 800f, 600f, color = hex(0xE5D2AE)))
            add(Op.RectOp(0f, 0f, 800f, 330f, color = hex(0xEAD8B4)))
            // wall frame
            add(Op.RectOp(96f, 70f, 150f, 112f, rx = 8f, color = hex(0xC8A570)))
            add(Op.RectOp(108f, 82f, 126f, 88f, rx = 4f, color = hex(0xEFE3C6)))
            add(Op.PathOp("M118 158 q28 -34 52 -12 q22 -40 52 -8 l0 32 l-104 0 Z", fill = hex(0xB4C29A)))
            add(Op.CircleOp(200f, 102f, 12f, hex(0xE8C97C)))
            // desk
            add(Op.RectOp(0f, 330f, 800f, 270f, color = hex(0xA57C50)))
            add(Op.RectOp(0f, 330f, 800f, 18f, color = hex(0x8F6A42)))
            add(Op.RectOp(0f, 348f, 800f, 6f, color = hex(0x7A5C3B), alpha = 0.6f))
            // mug
            add(Op.EllipseOp(614f, 352f, 70f, 12f, color = hex(0x5E4426), alpha = 0.28f))
            add(Op.RectOp(576f, 272f, 76f, 76f, rx = 12f, color = hex(0xB8593F)))
            add(Op.PathOp("M652 290 q34 6 28 34 q-6 24 -30 20 l0 -18 q14 2 16 -10 q2 -14 -14 -14 Z", fill = hex(0xA34C35)))
            add(Op.EllipseOp(614f, 278f, 30f, 8f, color = hex(0x4E3226)))
            add(
                Op.PathOp(
                    "M596 270 q4 -12 -4 -18 M614 268 q4 -14 -2 -22 M630 270 q6 -10 0 -18",
                    stroke = hex(0xF2E4C8), strokeWidth = 5f, capRound = true, alpha = 0.7f,
                ),
            )
            // notebook (rotated -4° about (230, 432))
            add(Op.EllipseOp(248f, 472f, 120f, 14f, color = hex(0x5E4426), alpha = 0.22f))
            add(
                Op.RectOp(
                    140f, 396f, 180f, 72f, rx = 10f, color = hex(0x8F9E6B),
                    rotate = -4f, pivotX = 230f, pivotY = 432f,
                ),
            )
            add(
                Op.RectOp(
                    150f, 404f, 160f, 56f, rx = 7f, color = hex(0xF2ECD8),
                    rotate = -4f, pivotX = 230f, pivotY = 432f,
                ),
            )
            for (i in 0 until 3) {
                add(
                    Op.RectOp(
                        164f, 416f + i * 14f, 118f - i * 26f, 6f, rx = 3f, color = hex(0xC9C2A8),
                        rotate = -4f, pivotX = 230f, pivotY = 432f,
                    ),
                )
            }
            // laptop (target)
            add(Op.EllipseOp(452f, 486f, 180f, 18f, color = hex(0x5E4426), alpha = 0.3f))
            add(
                Op.PathOp(
                    "M330 336 l244 0 q14 0 14 14 l0 118 l-272 0 l0 -118 q0 -14 14 -14 Z",
                    fill = hex(0x5B544A),
                ),
            )
            add(Op.RectOp(342f, 348f, 220f, 108f, rx = 6f, color = hex(0x7B7466)))
            add(Op.RectOp(352f, 358f, 200f, 88f, rx = 4f, color = hex(0x3E3A32)))
            add(Op.RectOp(366f, 372f, 90f, 10f, rx = 4f, color = hex(0xE8C97C), alpha = 0.85f))
            add(Op.RectOp(366f, 392f, 140f, 8f, rx = 4f, color = hex(0x8F897B), alpha = 0.8f))
            add(Op.RectOp(366f, 408f, 112f, 8f, rx = 4f, color = hex(0x8F897B), alpha = 0.6f))
            add(Op.PathOp("M296 468 l312 0 l16 22 q4 8 -8 8 l-328 0 q-12 0 -8 -8 Z", fill = hex(0x6B6459)))
            add(Op.RectOp(418f, 470f, 68f, 9f, rx = 4f, color = hex(0x57503F)))
        },
    )
}
