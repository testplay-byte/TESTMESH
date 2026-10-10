package com.testplaybyte.loom.ui.screens.export

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.testplaybyte.loom.domain.export.DatasetWriters
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.ToastIcon
import com.testplaybyte.loom.ui.LoomViewModel
import com.testplaybyte.loom.ui.components.LoomButton
import com.testplaybyte.loom.ui.components.LoomButtonSize
import com.testplaybyte.loom.ui.components.LoomButtonVariant
import com.testplaybyte.loom.ui.components.LoomCard
import com.testplaybyte.loom.ui.components.LoomMeter
import com.testplaybyte.loom.ui.components.LoomMeterTone
import com.testplaybyte.loom.ui.components.LoomPathPill
import com.testplaybyte.loom.ui.components.LoomRow
import com.testplaybyte.loom.ui.components.LoomScreenHeader
import com.testplaybyte.loom.ui.components.LoomStepper
import com.testplaybyte.loom.ui.components.LoomSwitch
import com.testplaybyte.loom.ui.components.SectionLabel
import com.testplaybyte.loom.ui.icons.IconAlert
import com.testplaybyte.loom.ui.icons.IconCheck
import com.testplaybyte.loom.ui.icons.IconFile
import com.testplaybyte.loom.ui.icons.IconShare
import com.testplaybyte.loom.ui.screens.project.FormatRows
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import java.io.File
import kotlinx.coroutines.launch

/**
 * §7 Export — ship the dataset (docs/03 §7).
 *
 * Format radios (persisted per project), options (brush masks, train/val
 * split), a real summary card, then a real write into the project folder
 * with live progress from the actual files → success card with the true
 * output path, a real share intent, and the JSON preview built from the
 * actual annotations.
 */
@Composable
fun ExportScreen(
    vm: LoomViewModel,
    projectId: String?,
    onBack: () -> Unit,
) {
    val c = loomColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by vm.projects.collectAsState()
    val workspacePath by vm.workspacePath.collectAsState()
    val project = projects.find { it.id == projectId }
        ?: run {
            com.testplaybyte.loom.ui.nav.MissingProjectScreen(onBackToProjects = onBack)
            return
        }

    var writing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<com.testplaybyte.loom.data.repo.LoomRepository.ExportResult?>(null) }

    val summary = remember(project) { DatasetWriters.summary(project) }
    val destination = if (workspacePath.isEmpty()) project.slug else "$workspacePath/${project.slug}"

    Column(modifier = Modifier.fillMaxSize()) {
        LoomScreenHeader(
            title = "Export dataset",
            subtitle = project.name,
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SectionLabel("Format")
            FormatRows(
                selected = project.format,
                onSelect = { fmt -> scope.launch { vm.repository.setFormat(project.id, fmt) } },
            )

            Spacer(Modifier.height(6.dp))
            SectionLabel("Options")
            LoomCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                LoomRow(
                    title = "Include brush masks",
                    sub = "Exclusion strokes export as mask polygons",
                    trailing = {
                        LoomSwitch(
                            checked = project.includeMasks,
                            onCheckedChange = { on ->
                                scope.launch { vm.repository.setExportOptions(project.id, includeMasks = on) }
                            },
                            contentDescription = "Include brush masks",
                        )
                    },
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
                LoomRow(
                    title = "Train/val split",
                    sub = "Held-out validation share",
                    trailing = {
                        LoomStepper(
                            value = project.splitPct,
                            min = 0,
                            max = LoomRules.SPLIT_MAX,
                            suffix = "%",
                            contentDescription = "Train validation split",
                            onChange = { v ->
                                scope.launch { vm.repository.setExportOptions(project.id, splitPct = v) }
                            },
                        )
                    },
                )
            }

            Spacer(Modifier.height(6.dp))
            SectionLabel("Summary")
            LoomCard(contentPadding = PaddingValues(14.dp)) {
                SummaryRow("Images", "${summary.images} images")
                SummaryRow("Meshes", "${summary.meshes} with meshes")
                SummaryRow("Prompt dots", "${summary.dots} dots")
                SummaryRow("Mask strokes", "${summary.strokes} strokes")
                Spacer(Modifier.height(10.dp))
                LoomPathPill(path = destination, modifier = Modifier.fillMaxWidth())
                if (summary.meshes == 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Tip: plant + dots in the canvas to generate meshes.",
                        style = loomType.small,
                        color = c.textMuted,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            if (!writing && result == null) {
                LoomButton(
                    onClick = {
                        writing = true
                        progress = 0f
                        scope.launch {
                            val r = vm.repository.exportDataset(project.id) { p -> progress = p }
                            writing = false
                            result = r
                            if (r != null) vm.toast("Export complete", ToastIcon.CHECK)
                        }
                    },
                    fullWidth = true,
                    label = "Export ${summary.images} images",
                )
            }

            if (writing) {
                LoomCard(contentPadding = PaddingValues(14.dp)) {
                    Text(
                        text = "Writing ${project.format.ext}…",
                        style = loomType.bodyStrong,
                        color = c.text,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoomMeter(value = progress * 100f, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "${Math.round(progress * 100)}%",
                            style = loomType.monoSmall,
                            color = c.textMuted,
                        )
                    }
                }
            }

            result?.let { r ->
                LoomCard(contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(34.dp).clip(CircleShape).background(c.success.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(IconCheck, contentDescription = null, tint = c.success, modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Export complete", style = loomType.title, color = c.text)
                            Text(
                                text = "${r.files} files written",
                                style = loomType.small,
                                color = c.textMuted,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LoomPathPill(path = r.outDir, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoomButton(
                            onClick = {
                                scope.launch { shareExport(context, vm, project.id, project.format) }
                            },
                            size = LoomButtonSize.SM,
                            variant = LoomButtonVariant.TONAL,
                            icon = IconShare,
                            label = "Share",
                        )
                        LoomButton(
                            onClick = { result = null },
                            size = LoomButtonSize.SM,
                            variant = LoomButtonVariant.GHOST,
                            label = "Done",
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("Preview")
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.canvas)
                            .border(1.dp, c.hairline, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = r.preview,
                            style = loomType.monoSmall.copy(fontSize = 10.5.sp),
                            color = c.textMuted,
                        )
                    }
                }
            }

            if (summary.meshes == 0 && !writing && result == null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(IconAlert, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Nothing meshed yet — exports will contain dots and tags only.",
                        style = loomType.small,
                        color = c.textMuted,
                    )
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") IconFile
}

@Composable
private fun SummaryRow(label: String, value: String) {
    val c = loomColors
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = loomType.small, color = c.textMuted, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = loomType.monoStat,
            color = c.text,
        )
    }
}

/**
 * Real share: copy the primary artifact out of the workspace into the app
 * cache and hand it to ACTION_SEND through a FileProvider (docs/06 §8).
 * Falls back to a warning toast when the copy fails.
 */
private suspend fun shareExport(
    context: android.content.Context,
    vm: LoomViewModel,
    projectId: String,
    format: com.testplaybyte.loom.domain.model.ExportFormat,
) {
    try {
        // Read through the workspace (SAF trees are not plain Files) and
        // stage a copy in the cache for the FileProvider.
        val name = if (format == com.testplaybyte.loom.domain.model.ExportFormat.COCO) {
            "instances.json"
        } else {
            "annotations.json"
        }
        val bytes = vm.repository.readExportFile(projectId, name)
        if (bytes == null) {
            vm.toast("Nothing to share yet", com.testplaybyte.loom.domain.model.ToastIcon.WARN)
            return
        }
        val cacheDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val target = File(cacheDir, name)
        target.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share dataset"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Could not share: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}
