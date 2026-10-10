package com.testplaybyte.loom.domain.model

/**
 * One toast message (docs/04 §10): single line, bottom-center, 2600ms.
 * The icon picks the glyph (check / info / mesh / save / warn).
 */
data class ToastMsg(val id: Long, val text: String, val icon: ToastIcon = ToastIcon.INFO)

enum class ToastIcon { CHECK, INFO, MESH, SAVE, WARN }
