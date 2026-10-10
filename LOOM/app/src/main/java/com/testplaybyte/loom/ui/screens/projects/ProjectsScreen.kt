package com.testplaybyte.loom.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import com.testplaybyte.loom.domain.model.ImageStatus
import com.testplaybyte.loom.domain.model.Project
import com.testplaybyte.loom.ui.LoomViewModel
import com.testplaybyte.loom.ui.components.LoomBottomNav
import com.testplaybyte.loom.ui.components.LoomButton
import com.testplaybyte.loom.ui.components.LoomButtonVariant
import com.testplaybyte.loom.ui.components.LoomCard
import com.testplaybyte.loom.ui.components.LoomFab
import com.testplaybyte.loom.ui.components.LoomPathPill
import com.testplaybyte.loom.ui.components.LoomScreenHeader
import com.testplaybyte.loom.ui.components.LoomSheet
import com.testplaybyte.loom.ui.components.LoomTab
import com.testplaybyte.loom.ui.components.ProjectImageArt
import com.testplaybyte.loom.ui.components.StatBlobPanel
import com.testplaybyte.loom.ui.icons.IconChevronRight
import com.testplaybyte.loom.ui.icons.IconFolderPlus
import com.testplaybyte.loom.ui.icons.IconTrash
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlinx.coroutines.launch

/**
 * §3 Projects (home) — the workspace (docs/03 §3).
 *
 * Every card: name + format chip + delete + chevron, the overlapping
 * thumbnail stack of the first four images (per-image status dots, "+N"
 * overflow chip), the folder path pill, and the signature
 * [StatBlobPanel] where the "Edited …" stamp sits in the empty area right
 * of the stats foot. FAB starts a new project; the trash opens the delete
 * confirm sheet. Bottom nav (Projects active) renders here.
 */
@Composable
fun ProjectsScreen(
    vm: LoomViewModel,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = loomColors
    val projects by vm.projects.collectAsState()
    val workspacePath by vm.workspacePath.collectAsState()
    var deleteId by remember { mutableStateOf<String?>(null) }
    val deleting = projects.find { it.id == deleteId }
    val scope = rememberCoroutineScope()

    // Refresh from disk whenever the screen appears: images added by hand
    // or folders dropped into the workspace are detected here.
    LaunchedEffect(Unit) { vm.repository.rescan() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LoomScreenHeader(
                title = "Projects",
                subtitle = buildString {
                    append("${projects.size} ")
                    append(if (projects.size == 1) "project" else "projects")
                    val images = projects.sumOf { it.images.size }
                    append(" · $images images")
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(top = 6.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (projects.isEmpty()) {
                    com.testplaybyte.loom.ui.components.LoomEmptyAction(
                        title = "No datasets yet",
                        sub = "Create a project to start annotating images and building your first dataset.",
                        actionLabel = "Create a project",
                        onAction = onNewProject,
                        icon = IconFolderPlus,
                        modifier = Modifier.padding(top = 40.dp),
                    )
                } else {
                    projects.forEach { p ->
                        ProjectCard(
                            project = p,
                            folderPath = if (workspacePath.isEmpty()) p.slug else "$workspacePath/${p.slug}",
                            onOpen = { onOpenProject(p.id) },
                            onDelete = { deleteId = p.id },
                            art = { image, modifier ->
                                ProjectImageArt(
                                    ws = vm.repository.currentWorkspace(),
                                    projectSlug = p.slug,
                                    image = image,
                                    modifier = modifier,
                                )
                            },
                        )
                    }
                }
            }
        }

        LoomFab(
            onClick = onNewProject,
            contentDescription = "New project",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 88.dp),
        )

        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            LoomBottomNav(active = LoomTab.PROJECTS, onSelect = {
                if (it == LoomTab.SETTINGS) onOpenSettings()
            })
        }
    }

    LoomSheet(
        open = deleting != null,
        onClose = { deleteId = null },
        title = "Delete project?",
    ) {
        Text(
            text = "Deletes the local project data. Images on disk are not touched.",
            style = loomType.small.copy(fontSize = 13.sp),
            color = c.textMuted,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LoomButton(
                onClick = {
                    deleting?.let { p ->
                        scope.launch {
                            vm.repository.deleteProject(p.id)
                            vm.toast("Project deleted", com.testplaybyte.loom.domain.model.ToastIcon.CHECK)
                        }
                    }
                    deleteId = null
                },
                fullWidth = true,
                variant = LoomButtonVariant.DANGER,
                label = "Delete",
            )
            LoomButton(
                onClick = { deleteId = null },
                fullWidth = true,
                variant = LoomButtonVariant.GHOST,
                label = "Cancel",
            )
        }
    }
}

/** One project card — exact anatomy from docs/03 §3. */
@Composable
private fun ProjectCard(
    project: Project,
    folderPath: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    art: @Composable (com.testplaybyte.loom.domain.model.ProjectImage, Modifier) -> Unit,
) {
    val c = loomColors
    LoomCard(
        onClick = onOpen,
        contentPadding = PaddingValues(14.dp),
    ) {
        // 1. title row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = project.name,
                style = loomType.title.copy(fontSize = 15.5.sp, fontWeight = FontWeight.W600),
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            // format chip (mono, uppercase, tiny)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.surface2)
                    .border(1.dp, c.hairline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = project.format.wire.uppercase(),
                    style = loomType.monoChip.copy(fontSize = 9.5.sp, fontWeight = FontWeight.W500),
                    color = c.textMuted,
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(IconTrash, contentDescription = "Delete ${project.name}", tint = c.textMuted, modifier = Modifier.size(16.dp))
            }
            Icon(IconChevronRight, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.height(10.dp))
        ThumbStack(project, art)
        Spacer(Modifier.height(10.dp))

        // 3. folder path pill
        LoomPathPill(path = folderPath, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(10.dp))

        // 4. the signature stepped blob
        StatBlobPanel(
            totalImages = project.images.size,
            doneCount = project.doneCount,
            draftCount = project.draftCount,
            pct = project.annotatedPct,
            editedText = "Edited ${timeAgo(project.updatedAt)}",
        )
    }
}

/** First four images as overlapping 64×48 slices + status dots + "+N". */
@Composable
private fun ThumbStack(
    project: Project,
    art: @Composable (com.testplaybyte.loom.domain.model.ProjectImage, Modifier) -> Unit,
) {
    val c = loomColors
    val thumbs = project.images.take(4)
    val extra = project.images.size - thumbs.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (thumbs.isNotEmpty()) {
            // A fixed-width Box with stepped placement: every thumb after
            // the first overlaps the previous by 14dp (a plain Row + offset
            // would still reserve the full width and leave gaps).
            val step = 50.dp
            Box(
                modifier = Modifier
                    .width(64.dp + step * (thumbs.size - 1))
                    .height(48.dp),
            ) {
                thumbs.forEachIndexed { i, im ->
                    Box(
                        modifier = Modifier
                            .offset(x = step * i)
                            .size(width = 64.dp, height = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.surface2)
                            .border(1.dp, c.hairlineStrong, RoundedCornerShape(8.dp)),
                    ) {
                        art(im, Modifier.fillMaxWidth().height(48.dp))
                    }
                }
                // Status dots drawn AFTER the thumbs, on top: a dot tucked
                // into a thumb's top-right corner would be covered by the
                // next overlapping thumbnail.
                thumbs.forEachIndexed { i, im ->
                    val status = project.stateOf(im.id).status
                    if (status != ImageStatus.UNLABELED) {
                        com.testplaybyte.loom.ui.components.StatusDot(
                            status = status,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = step * i + 64.dp - 12.dp, y = 4.dp),
                            punchOutColor = c.surface1,
                        )
                    }
                }
            }
        }
        if (extra > 0) {
            Spacer(Modifier.width(7.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(c.surface2)
                    .border(1.dp, c.hairline, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "+$extra",
                    style = loomType.monoChip.copy(fontSize = 10.5.sp, fontWeight = FontWeight.W600),
                    color = c.textMuted,
                )
            }
        }
    }
}

/** Compact relative time (docs/03 §3): just now | {n}m | {n}h | {n}d | {n}w. */
fun timeAgo(ts: Long): String {
    val s = maxOf(1L, (System.currentTimeMillis() - ts) / 1000)
    if (s < 60) return "just now"
    val m = s / 60
    if (m < 60) return "${m}m ago"
    val h = m / 60
    if (h < 24) return "${h}h ago"
    val d = h / 24
    if (d < 7) return "${d}d ago"
    return "${d / 7}w ago"
}
