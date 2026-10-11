package com.testplaybyte.loom.ui.screens.newproject

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
import androidx.compose.foundation.layout.imePadding
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
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlinx.coroutines.launch

/**
 * §4 New project — two steps, one scrollable screen: Name (32 chars +
 * live slug preview showing exactly where the folder will land) and
 * Labels (chips, max 8, seed "Object").
 *
 * There is deliberately NO location picker and NO image picking: the
 * folder is always the user's storage folder + the slugified name, and
 * the project starts EMPTY — images are added afterwards from the
 * project screen or by dropping files into the folder.
 *
 * Create is always fully opaque (never a washed-out disabled state);
 * with a blank name it asks for one via toast. `imePadding` keeps the
 * button above the keyboard while typing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewProjectScreen(
    vm: LoomViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val c = loomColors
    val scope = rememberCoroutineScope()
    val workspacePath by vm.workspacePath.collectAsState()

    var name by remember { mutableStateOf("") }
    val labels = remember { mutableStateListOf<String>() }

    var labelSheet by remember { mutableStateOf(false) }
    var labelDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val slug = LoomRules.slugify(name.ifEmpty { "dataset" })
    val folderPath = "${workspacePath.ifEmpty { "LoomDatasets" }}/$slug"

    Column(
        modifier = Modifier
            .fillMaxSize()
            // keep the Create button above the keyboard while typing
            .imePadding(),
    ) {
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

            // ── 2. Labels ───────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionLabel("2. Labels")
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
                        )
                        busy = false
                        vm.toast("Project created — add images next", ToastIcon.CHECK)
                        onCreated(project.id)
                    }
                },
                fullWidth = true,
                enabled = !busy,
                label = "Create project",
            )
            Text(
                text = "A folder named \u201C$slug\u201D is created in your storage folder. " +
                    "Each image gets its own LabelMe .json beside it as you annotate.",
                style = loomType.small,
                color = c.textMuted,
            )
            Spacer(Modifier.height(8.dp))
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
        Text("Classes name your objects — add the ones you need (up to 8).", style = loomType.small, color = c.textMuted)
    }
}
