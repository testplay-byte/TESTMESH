package com.testplaybyte.loom.ui.screens.newproject

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.ToastIcon
import com.testplaybyte.loom.ui.LoomViewModel
import com.testplaybyte.loom.ui.components.LabelChip
import com.testplaybyte.loom.ui.components.LoomButton
import com.testplaybyte.loom.ui.components.LoomButtonSize
import com.testplaybyte.loom.ui.components.LoomButtonVariant
import com.testplaybyte.loom.ui.components.LoomCard
import com.testplaybyte.loom.ui.components.LoomChip
import com.testplaybyte.loom.ui.components.LoomInput
import com.testplaybyte.loom.ui.components.LoomPathPill
import com.testplaybyte.loom.ui.components.LoomScreenHeader
import com.testplaybyte.loom.ui.components.LoomSheet
import com.testplaybyte.loom.ui.components.SectionLabel
import com.testplaybyte.loom.ui.icons.IconCheck
import com.testplaybyte.loom.ui.icons.IconChevronRight
import com.testplaybyte.loom.ui.icons.IconFolder
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlinx.coroutines.launch

/**
 * §4 New project — a 3-step creation form on one scrollable screen
 * (docs/03 §4, trimmed for the real app): Name (32 chars + live slug
 * preview) → Location (folder picker sheet: workspace root, existing
 * subfolders, create-new-folder, plus a real SAF change-storage action)
 * → Labels (chips, max 8, seed "Object").
 *
 * The project starts EMPTY — real images are added afterwards from the
 * project screen (photo picker) or by dropping files into the project
 * folder, where the rescan picks them up. There is deliberately no
 * image/scene picking here.
 *
 * Create is always live: with a blank name it asks for one instead of
 * sitting in a washed-out disabled state. → toast `Project created` →
 * the new project's detail screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewProjectScreen(
    vm: LoomViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val c = loomColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspacePath by vm.workspacePath.collectAsState()
    val perms by vm.perms.collectAsState()

    var name by remember { mutableStateOf("") }
    var parentFolder by remember { mutableStateOf("") } // "" = workspace root
    val labels = remember { mutableStateListOf("Object") }
    val subfolders = remember { mutableStateListOf<String>() }

    var locationSheet by remember { mutableStateOf(false) }
    var labelSheet by remember { mutableStateOf(false) }
    var newFolderDraft by remember { mutableStateOf("") }
    var labelDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        subfolders.clear()
        subfolders.addAll(vm.repository.workspaceSubfolders())
    }

    // Real SAF picker for changing the storage folder from the Location sheet.
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // non-persistable provider: continues for this session
            }
            scope.launch {
                val display = try {
                    DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/')
                } catch (e: Exception) {
                    uri.lastPathSegment ?: "LoomDatasets"
                }
                vm.repository.setFolder(uri.toString(), display.ifEmpty { "LoomDatasets" })
                subfolders.clear()
                subfolders.addAll(vm.repository.workspaceSubfolders())
                parentFolder = ""
                vm.toast("Permission granted", ToastIcon.CHECK)
            }
        }
    }

    val slug = LoomRules.slugify(name.ifEmpty { "dataset" })
    val folderPath = buildString {
        append(workspacePath.ifEmpty { "LoomDatasets" })
        if (parentFolder.isNotEmpty()) append("/").append(parentFolder)
        append("/").append(slug)
    }
    val canCreate = name.isNotBlank() && !busy

    Column(modifier = Modifier.fillMaxSize()) {
        LoomScreenHeader(title = "New project", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 1. Name ─────────────────────────────────────────────────────
            Spacer(Modifier.height(6.dp))
            SectionLabel("1. Name", trailing = {
                Text(
                    text = "${name.length}/${LoomRules.PROJECT_NAME_MAX}",
                    style = loomType.monoSmall,
                    color = c.textMuted,
                )
            })
            LoomInput(
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. Street scenes v1",
                maxLength = LoomRules.PROJECT_NAME_MAX,
                modifier = Modifier.fillMaxWidth(),
            )
            LoomPathPill(path = folderPath, modifier = Modifier.fillMaxWidth())

            // ── 2. Location ─────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionLabel("2. Location")
            LoomCard(
                onClick = {
                    newFolderDraft = ""
                    locationSheet = true
                },
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(IconFolder, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (parentFolder.isEmpty()) "Workspace root" else parentFolder,
                            style = loomType.title,
                            color = c.text,
                        )
                        Text("Tap to choose a folder", style = loomType.small, color = c.textMuted)
                    }
                    Icon(IconChevronRight, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                }
            }

            // ── 3. Labels ───────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionLabel("3. Labels")
            LoomCard(contentPadding = PaddingValues(14.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    labels.forEachIndexed { i, l ->
                        LabelChip(
                            name = l,
                            colorToken = "c${(i % 8) + 1}",
                            active = false,
                            onRemove = { labels.remove(l) },
                        )
                    }
                    LoomChip(
                        label = "＋ Add label",
                        onClick = {
                            labelDraft = ""
                            labelSheet = true
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            LoomButton(
                onClick = {
                    if (busy) return@LoomButton
                    if (name.isBlank()) {
                        vm.toast("Give the project a name first", ToastIcon.WARN)
                        return@LoomButton
                    }
                    busy = true
                    scope.launch {
                        val project = vm.repository.createProject(
                            name = name,
                            // Projects start EMPTY — images are added from the
                            // project screen or dropped into the folder.
                            sceneIds = emptyList(),
                            labelNames = labels.take(LoomRules.LABEL_MAX).toList(),
                            parentFolder = parentFolder.ifEmpty { null },
                        )
                        busy = false
                        vm.toast("Project created — add images next", ToastIcon.CHECK)
                        onCreated(project.id)
                    }
                },
                fullWidth = true,
                enabled = canCreate,
                label = "Create project",
            )
            Text(
                text = if (perms.files) {
                    "Loom writes images and annotations.json into the project folder as you work."
                } else {
                    "Loom will request file access before writing."
                },
                style = loomType.small,
                color = c.textMuted,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    // ── Location sheet ────────────────────────────────────────────────────
    LoomSheet(open = locationSheet, onClose = { locationSheet = false }, title = "Choose folder") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FolderOption(
                path = workspacePath.ifEmpty { "LoomDatasets" },
                selected = parentFolder.isEmpty(),
            ) {
                parentFolder = ""
                locationSheet = false
            }
            subfolders.forEach { sub ->
                FolderOption(
                    path = "${workspacePath.ifEmpty { "LoomDatasets" }}/$sub",
                    selected = parentFolder == sub,
                ) {
                    parentFolder = sub
                    locationSheet = false
                }
            }
            Spacer(Modifier.height(6.dp))
            LoomInput(
                value = newFolderDraft,
                onValueChange = { newFolderDraft = it },
                placeholder = "folder-name",
                maxLength = LoomRules.SLUG_MAX,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoomButton(
                    onClick = {
                        if (newFolderDraft.isBlank()) return@LoomButton
                        val sub = LoomRules.slugify(newFolderDraft)
                        scope.launch {
                            vm.repository.createWorkspaceSubfolder(sub)
                            subfolders.clear()
                            subfolders.addAll(vm.repository.workspaceSubfolders())
                            parentFolder = sub
                            locationSheet = false
                        }
                    },
                    size = LoomButtonSize.SM,
                    label = "Create",
                )
                LoomButton(
                    onClick = { folderLauncher.launch(null) },
                    size = LoomButtonSize.SM,
                    variant = LoomButtonVariant.TONAL,
                    label = "Change storage folder…",
                )
            }
        }
    }

    // ── Add label sheet ───────────────────────────────────────────────────
    LoomSheet(open = labelSheet, onClose = { labelSheet = false }, title = "Add label") {
        LoomInput(
            value = labelDraft,
            onValueChange = { labelDraft = it },
            placeholder = "e.g. Bicycle",
            maxLength = LoomRules.LABEL_NAME_MAX,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        LoomButton(
            onClick = {
                val clean = LoomRules.cleanLabelName(labelDraft)
                when {
                    clean.isEmpty() -> Unit
                    labels.size >= LoomRules.LABEL_MAX -> vm.toast("Label limit reached", ToastIcon.WARN)
                    LoomRules.isDuplicate(labels.toList(), clean) -> vm.toast("Label already exists", ToastIcon.WARN)
                    else -> {
                        labels.add(clean)
                        labelSheet = false
                    }
                }
            },
            size = LoomButtonSize.SM,
            label = "Add",
        )
        Spacer(Modifier.height(8.dp))
        Text("Up to 8 classes per project.", style = loomType.small, color = c.textMuted)
    }
}

/** Base-dir row: mono path + mint check when selected. */
@Composable
private fun FolderOption(path: String, selected: Boolean, onClick: () -> Unit) {
    val c = loomColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = path,
            style = loomType.monoSmall,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(IconCheck, contentDescription = null, tint = c.pos, modifier = Modifier.size(16.dp))
        }
    }
}
