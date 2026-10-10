package com.testplaybyte.loom.ui.screens.project

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.data.scene.SceneLibrary
import com.testplaybyte.loom.domain.model.ExportFormat
import com.testplaybyte.loom.domain.model.ImageStatus
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.ToastIcon
import com.testplaybyte.loom.ui.LoomViewModel
import com.testplaybyte.loom.ui.components.ArtFrame
import com.testplaybyte.loom.ui.components.LabelChip
import com.testplaybyte.loom.ui.components.LoomButton
import com.testplaybyte.loom.ui.components.LoomButtonSize
import com.testplaybyte.loom.ui.components.LoomButtonVariant
import com.testplaybyte.loom.ui.components.LoomCard
import com.testplaybyte.loom.ui.components.LoomInput
import com.testplaybyte.loom.ui.components.LoomMeter
import com.testplaybyte.loom.ui.components.LoomMeterTone
import com.testplaybyte.loom.ui.components.LoomPathPill
import com.testplaybyte.loom.ui.components.LoomScreenHeader
import com.testplaybyte.loom.ui.components.LoomSheet
import com.testplaybyte.loom.ui.components.MasonryGrid
import com.testplaybyte.loom.ui.components.ProjectImageArt
import com.testplaybyte.loom.ui.components.SectionLabel
import com.testplaybyte.loom.ui.components.StatusChip
import com.testplaybyte.loom.ui.components.tileAspect
import com.testplaybyte.loom.ui.icons.IconCheck
import com.testplaybyte.loom.ui.icons.IconExport
import com.testplaybyte.loom.ui.icons.IconImage
import com.testplaybyte.loom.ui.icons.IconMore
import com.testplaybyte.loom.ui.icons.IconTrash
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlinx.coroutines.launch

/**
 * §5 Project detail — one dataset (docs/03 §5): centered stats card,
 * single-line label row with an Edit manager, the masonry image grid
 * (two columns, per-image aspect, shortest-column flow) and the action
 * stack. The overflow sheet carries the format selector, the folder path
 * and delete.
 *
 * ADDED (documented, necessary for a real dataset tool): an "Add images"
 * header action that copies photos picked from the system picker into the
 * project folder — the counterpart of the workspace scanner that detects
 * images the user drops in by hand.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectDetailScreen(
    vm: LoomViewModel,
    projectId: String?,
    onBack: () -> Unit,
    onAnnotate: (Int) -> Unit,
    onExport: () -> Unit,
) {
    val c = loomColors
    val projects by vm.projects.collectAsState()
    val workspacePath by vm.workspacePath.collectAsState()
    val project = projects.find { it.id == projectId }

    if (project == null) {
        com.testplaybyte.loom.ui.nav.MissingProjectScreen(onBackToProjects = onBack)
        return
    }

    val scope = rememberCoroutineScope()
    var overflowOpen by remember { mutableStateOf(false) }
    var labelsSheet by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }

    // The folder is the database: refresh states/images whenever the screen
    // appears (files may have changed on disk).
    LaunchedEffect(projectId) { vm.repository.rescan() }

    // Real photo picker (multi-select) → copy into the project folder.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch { vm.repository.addImages(project.id, uris) }
        }
    }

    val folderPath = if (workspacePath.isEmpty()) project.slug else "$workspacePath/${project.slug}"

    Column(modifier = Modifier.fillMaxSize()) {
        LoomScreenHeader(
            title = project.name,
            subtitle = "${project.images.size} images · ${project.doneCount} done",
            onBack = onBack,
            actions = {
                com.testplaybyte.loom.ui.components.LoomIconButton(
                    icon = IconImage,
                    contentDescription = "Add images",
                    onClick = {
                        photoPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                com.testplaybyte.loom.ui.components.LoomIconButton(
                    icon = IconExport,
                    contentDescription = "Export dataset",
                    onClick = onExport,
                )
                com.testplaybyte.loom.ui.components.LoomIconButton(
                    icon = IconMore,
                    contentDescription = "Project options",
                    onClick = { overflowOpen = true },
                )
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ── stats card (centered) ──────────────────────────────────────
            LoomCard(contentPadding = PaddingValues(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatCell(project.images.size.toString(), "Images", Modifier.weight(1f))
                    StatCell("${Math.round(project.annotatedPct)}%", "Annotated", Modifier.weight(1f))
                    StatCell(project.labels.size.toString(), "Labels", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                LoomMeter(
                    value = project.annotatedPct,
                    tone = if (project.annotatedPct >= 100f) LoomMeterTone.GOOD else LoomMeterTone.ACCENT,
                )
            }

            // ── labels row (single line, never wraps) ─────────────────────
            SectionLabel("Labels", trailing = {
                LoomButton(
                    onClick = { labelsSheet = true },
                    size = LoomButtonSize.SM,
                    variant = LoomButtonVariant.GHOST,
                    label = "Edit",
                )
            })
            if (project.labels.isEmpty()) {
                Text(
                    text = "No labels yet — tap Edit to add the classes you need.",
                    style = loomType.small,
                    color = c.textMuted,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    project.labels.forEach { l ->
                        LabelChip(name = l.name, colorToken = l.color, active = false)
                    }
                }
            }

            // ── masonry images ────────────────────────────────────────────
            Spacer(Modifier.height(2.dp))
            SectionLabel("Images", trailing = {
                Text("${project.images.size}", style = loomType.monoSmall, color = c.textMuted)
            })
            if (project.images.isEmpty()) {
                Text(
                    text = "No images yet — add images or drop files into the project folder.",
                    style = loomType.small,
                    color = c.textMuted,
                )
            } else {
                MasonryGrid(
                    items = project.images,
                    keyOf = { it.id },
                    aspectOf = { tileAspect(it.id) },
                ) { image ->
                    val index = project.images.indexOf(image)
                    ImageTile(
                        index = index,
                        status = project.stateOf(image.id).status,
                        fileName = image.file,
                        targetName = image.sceneId
                            ?.let { SceneLibrary.byId(it).target.name }
                            ?: project.labels.firstOrNull { it.id == project.stateOf(image.id).labelId }?.name
                            ?: "—",
                        aspect = tileAspect(image.id),
                        onClick = { onAnnotate(index) },
                    ) {
                        ProjectImageArt(
                            ws = vm.repository.currentWorkspace(),
                            projectSlug = project.slug,
                            image = image,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            // ── actions ───────────────────────────────────────────────────
            val nextIndex = project.images.indexOfFirst {
                project.stateOf(it.id).status != ImageStatus.DONE
            }
            LoomButton(
                onClick = { if (nextIndex >= 0) onAnnotate(nextIndex) },
                fullWidth = true,
                enabled = nextIndex >= 0,
                label = "Annotate next",
            )
            if (nextIndex < 0) {
                Text(
                    text = "All images annotated",
                    style = loomType.small,
                    color = c.textMuted,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            LoomButton(
                onClick = onExport,
                fullWidth = true,
                variant = LoomButtonVariant.TONAL,
                label = "Export dataset",
            )
        }
    }

    // ── overflow sheet ────────────────────────────────────────────────────
    LoomSheet(open = overflowOpen, onClose = { overflowOpen = false }, title = "Project") {
        SectionLabel("Format")
        FormatRows(
            selected = project.format,
            onSelect = { fmt ->
                scope.launch { vm.repository.setFormat(project.id, fmt) }
            },
        )
        Spacer(Modifier.height(12.dp))
        SectionLabel("Folder")
        LoomPathPill(path = folderPath, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    overflowOpen = false
                    deleteConfirm = true
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(IconTrash, contentDescription = null, tint = c.error, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Text("Delete project", style = loomType.body, color = c.error)
        }
    }

    // ── manage labels sheet ───────────────────────────────────────────────
    LoomSheet(open = labelsSheet, onClose = { labelsSheet = false }, title = "Manage labels") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            project.labels.forEach { l ->
                LabelChip(
                    name = l.name,
                    colorToken = l.color,
                    active = false,
                    onRemove = {
                        scope.launch { vm.repository.removeLabel(project.id, l.id) }
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoomInput(
                value = newLabel,
                onValueChange = { newLabel = it },
                placeholder = "New label name",
                maxLength = LoomRules.LABEL_NAME_MAX,
                modifier = Modifier.weight(1f),
            )
            LoomButton(
                onClick = {
                    scope.launch {
                        val err = vm.repository.addLabel(project.id, newLabel)
                        if (err != null) {
                            vm.toast(err, ToastIcon.WARN)
                        } else {
                            newLabel = ""
                        }
                    }
                },
                size = LoomButtonSize.SM,
                label = "Add",
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Labels used by the smart model to name each annotation.",
            style = loomType.small,
            color = c.textMuted,
        )
    }

    // ── delete confirm ────────────────────────────────────────────────────
    LoomSheet(open = deleteConfirm, onClose = { deleteConfirm = false }, title = "Delete project?") {
        Text(
            text = "Deletes the local project data. Images on disk are not touched.",
            style = loomType.small.copy(fontSize = 13.sp),
            color = c.textMuted,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LoomButton(
                onClick = {
                    deleteConfirm = false
                    scope.launch {
                        vm.repository.deleteProject(project.id)
                        vm.toast("Project deleted", ToastIcon.CHECK)
                    }
                    onBack()
                },
                fullWidth = true,
                variant = LoomButtonVariant.DANGER,
                label = "Delete",
            )
            LoomButton(
                onClick = { deleteConfirm = false },
                fullWidth = true,
                variant = LoomButtonVariant.GHOST,
                label = "Cancel",
            )
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    val c = loomColors
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = loomType.monoBig,
            color = c.text,
        )
        Text(text = label, style = loomType.small, color = c.textMuted)
    }
}

/** One masonry tile: art (own aspect) + status chip + index + meta line. */
@Composable
private fun ImageTile(
    index: Int,
    status: ImageStatus,
    fileName: String,
    targetName: String,
    aspect: Float,
    onClick: () -> Unit,
    art: @Composable () -> Unit,
) {
    val c = loomColors
    Column(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Box {
            ArtFrame(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect),
                radius = 8.dp,
            ) {
                art()
                StatusChip(
                    status = status,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
                Text(
                    text = String.format(java.util.Locale.US, "%02d", index + 1),
                    style = loomType.monoChip.copy(fontWeight = FontWeight.W600),
                    color = c.text,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(c.surface1.copy(alpha = 0.85f), CircleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = fileName,
            style = loomType.monoSmall,
            color = c.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = targetName,
            style = loomType.smallStrong,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The four format rows (shared by Project overflow + Export) — docs/03 §5. */
@Composable
fun FormatRows(
    selected: ExportFormat,
    onSelect: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ExportFormat.entries.forEach { fmt ->
            val active = fmt == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) c.surface2 else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(fmt) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(fmt.displayName, style = loomType.bodyStrong, color = c.text)
                    Text(
                        text = "${fmt.ext} · ${fmt.note}",
                        style = loomType.monoSmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (active) {
                    Icon(IconCheck, contentDescription = null, tint = c.primary, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}
