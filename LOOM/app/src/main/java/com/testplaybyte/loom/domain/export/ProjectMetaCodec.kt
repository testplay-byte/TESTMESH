package com.testplaybyte.loom.domain.export

import com.testplaybyte.loom.domain.model.ExportFormat
import com.testplaybyte.loom.domain.model.Label
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.Project
import com.testplaybyte.loom.domain.model.ProjectImage
import org.json.JSONArray
import org.json.JSONObject

/**
 * `loom-project.json` — the app's project registry file inside every
 * project folder. Holds what `annotations.json` (a pure export-format
 * document) deliberately does not: stable ids, the image list with real
 * pixel dimensions and scene references, export options, and timestamps.
 *
 * The folder is the database (user requirement): images + annotations.json
 * are the user's data; this file is the lightweight app metadata that lets
 * the workspace be re-scanned and re-opened. It is tolerant on read —
 * unknown/missing fields fall back to defaults — so hand-edited or
 * older folders never block loading.
 */
object ProjectMetaCodec {

    const val FILE_NAME = "loom-project.json"
    const val META_VERSION = 1

    fun encode(project: Project): String {
        val root = JSONObject()
        root.put("app", "loom")
        root.put("metaVersion", META_VERSION)
        root.put("id", project.id)
        root.put("slug", project.slug)
        root.put("name", project.name)
        root.put("createdAt", project.createdAt)
        root.put("updatedAt", project.updatedAt)
        root.put("format", project.format.wire)
        root.put("includeMasks", project.includeMasks)
        root.put("splitPct", project.splitPct)
        root.put(
            "labels",
            JSONArray().apply {
                for (l in project.labels) {
                    put(
                        JSONObject().apply {
                            put("id", l.id)
                            put("name", l.name)
                            put("color", l.color)
                        },
                    )
                }
            },
        )
        root.put(
            "images",
            JSONArray().apply {
                for (im in project.images) {
                    put(
                        JSONObject().apply {
                            put("id", im.id)
                            put("file", im.file)
                            if (im.sceneId != null) put("sceneId", im.sceneId)
                            put("width", im.width)
                            put("height", im.height)
                        },
                    )
                }
            },
        )
        return root.toString(2)
    }

    /**
     * Decode a `loom-project.json`. [folderName] is used as a fallback slug
     * (and name) when the file omits them.
     */
    fun decode(text: String, folderName: String): Project {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid loom-project.json", e)
        }
        val labels = ArrayList<Label>()
        val labelsArr = root.optJSONArray("labels") ?: JSONArray()
        for (i in 0 until labelsArr.length()) {
            val o = labelsArr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").ifEmpty { continue }
            labels.add(
                Label(
                    id = o.optString("id", "lb-$i"),
                    name = name,
                    color = o.optString("color", "c${(i % 8) + 1}"),
                ),
            )
        }
        val images = ArrayList<ProjectImage>()
        val imagesArr = root.optJSONArray("images") ?: JSONArray()
        for (i in 0 until imagesArr.length()) {
            val o = imagesArr.optJSONObject(i) ?: continue
            val file = o.optString("file", "").ifEmpty { continue }
            val w = o.optInt("width", 800).let { if (it > 0) it else 800 }
            val h = o.optInt("height", 600).let { if (it > 0) it else 600 }
            images.add(
                ProjectImage(
                    id = o.optString("id", "im-$i"),
                    file = file,
                    sceneId = if (o.isNull("sceneId")) null else o.optString("sceneId", "").ifEmpty { null },
                    width = w,
                    height = h,
                ),
            )
        }
        val slug = root.optString("slug", "").ifEmpty { LoomRules.slugify(folderName) }
        val name = root.optString("name", "").ifEmpty { folderName }
        return Project(
            id = root.optString("id", "pr-$slug"),
            slug = slug,
            name = name,
            createdAt = root.optLong("createdAt", 0L),
            updatedAt = root.optLong("updatedAt", 0L),
            images = images,
            labels = labels,
            format = ExportFormat.fromWire(root.optString("format", null)),
            includeMasks = root.optBoolean("includeMasks", true),
            splitPct = LoomRules.clampSplit(root.optInt("splitPct", 20)),
            states = emptyMap(),
        )
    }
}
