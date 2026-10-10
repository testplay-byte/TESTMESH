package com.testplaybyte.loom.ui.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.testplaybyte.loom.domain.model.ExportFormat
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.LoomSettings
import com.testplaybyte.loom.domain.model.ThemeMode
import com.testplaybyte.loom.domain.model.ToastIcon
import com.testplaybyte.loom.ui.LoomViewModel
import com.testplaybyte.loom.ui.components.LoomBottomNav
import com.testplaybyte.loom.ui.components.LoomButton
import com.testplaybyte.loom.ui.components.LoomButtonSize
import com.testplaybyte.loom.ui.components.LoomButtonVariant
import com.testplaybyte.loom.ui.components.LoomCard
import com.testplaybyte.loom.ui.components.LoomRow
import com.testplaybyte.loom.ui.components.LoomScreenHeader
import com.testplaybyte.loom.ui.components.LoomSegmented
import com.testplaybyte.loom.ui.components.LoomSheet
import com.testplaybyte.loom.ui.components.LoomStepper
import com.testplaybyte.loom.ui.components.LoomSwitch
import com.testplaybyte.loom.ui.components.LoomTab
import com.testplaybyte.loom.ui.components.SectionLabel
import com.testplaybyte.loom.ui.components.SegOption
import com.testplaybyte.loom.ui.icons.IconDatabase
import com.testplaybyte.loom.ui.icons.IconMoon
import com.testplaybyte.loom.ui.icons.IconSun
import com.testplaybyte.loom.ui.icons.IconTrash
import com.testplaybyte.loom.ui.screens.project.FormatRows
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlinx.coroutines.launch

/**
 * §8 Settings (docs/03 §8): appearance (theme), canvas defaults (dot size,
 * density, loupe, haptics), annotation (undo depth, autosave), the default
 * export format for NEW projects, a real storage readout of Loom's own
 * data, clear-all-data, and the about rows.
 *
 * Everything persists through DataStore and applies app-wide (theme
 * included). The scroll end is padded so the last rows clear the bottom
 * nav.
 */
@Composable
fun SettingsScreen(
    vm: LoomViewModel,
    onBack: () -> Unit,
) {
    val c = loomColors
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()
    val projects by vm.projects.collectAsState()
    val workspacePath by vm.workspacePath.collectAsState()
    var clearOpen by remember { mutableStateOf(false) }

    // Real storage readout of the data Loom itself writes (bytes → KB).
    val storageKb by produceState(0L, projects) {
        value = vm.repository.storageBytes() / 1024L
    }

    fun patch(next: LoomSettings) {
        scope.launch { vm.repository.patchSettings(next) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LoomScreenHeader(title = "Settings", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 96.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            SectionLabel("Appearance")
            LoomCard(contentPadding = PaddingValues(14.dp)) {
                Text("Theme", style = loomType.bodyStrong, color = c.text)
                Text("Applies to the device", style = loomType.small, color = c.textMuted)
                Spacer(Modifier.height(10.dp))
                LoomSegmented(
                    value = settings.theme,
                    options = listOf(
                        SegOption(ThemeMode.DARK, "Dark", IconMoon),
                        SegOption(ThemeMode.LIGHT, "Light", IconSun),
                    ),
                    onSelect = { patch(settings.copy(theme = it)) },
                    contentDescription = "Theme",
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("Canvas defaults")
            LoomCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                // dot size S | M | L
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Dot size", style = loomType.bodyStrong, color = c.text)
                    Spacer(Modifier.height(8.dp))
                    LoomSegmented(
                        value = settings.dotSize,
                        options = listOf(
                            SegOption(8, "S", null),
                            SegOption(10, "M", null),
                            SegOption(14, "L", null),
                        ),
                        onSelect = { patch(settings.copy(dotSize = it)) },
                        contentDescription = "Dot size",
                    )
                }
                Divider()
                LoomRow(
                    title = "Mesh density",
                    sub = "Vertices per generated mesh",
                    trailing = {
                        LoomStepper(
                            value = settings.defaultDensity,
                            min = LoomRules.DEFAULT_DENSITY_MIN,
                            max = LoomRules.DEFAULT_DENSITY_MAX,
                            suffix = "pts",
                            contentDescription = "Default mesh density",
                            onChange = { patch(settings.copy(defaultDensity = it)) },
                        )
                    },
                )
                Divider()
                LoomRow(
                    title = "Magnifier loupe",
                    sub = "Zoom lens while dragging vertices",
                    trailing = {
                        LoomSwitch(
                            checked = settings.magnifier,
                            onCheckedChange = { patch(settings.copy(magnifier = it)) },
                            contentDescription = "Magnifier loupe",
                        )
                    },
                )
                Divider()
                LoomRow(
                    title = "Haptic feedback",
                    sub = "Visual pulse on commit actions",
                    trailing = {
                        LoomSwitch(
                            checked = settings.haptics,
                            onCheckedChange = { patch(settings.copy(haptics = it)) },
                            contentDescription = "Haptic feedback",
                        )
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("Annotation")
            LoomCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                LoomRow(
                    title = "Undo depth",
                    trailing = {
                        LoomStepper(
                            value = settings.undoDepth,
                            min = LoomRules.UNDO_MIN,
                            max = LoomRules.UNDO_MAX,
                            step = LoomRules.UNDO_STEP,
                            contentDescription = "Undo depth",
                            onChange = { patch(settings.copy(undoDepth = it)) },
                        )
                    },
                )
                Divider()
                LoomRow(
                    title = "Autosave",
                    sub = "Persist after every gesture",
                    trailing = {
                        LoomSwitch(
                            checked = settings.autosave,
                            onCheckedChange = { patch(settings.copy(autosave = it)) },
                            contentDescription = "Autosave",
                        )
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("Export")
            LoomCard(contentPadding = PaddingValues(14.dp)) {
                Text(
                    text = "Default format for new projects",
                    style = loomType.bodyStrong,
                    color = c.text,
                )
                Spacer(Modifier.height(8.dp))
                FormatRows(
                    selected = settings.defaultFormat,
                    onSelect = { fmt: ExportFormat -> patch(settings.copy(defaultFormat = fmt)) },
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("Data")
            LoomCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                LoomRow(
                    title = "Local storage",
                    icon = IconDatabase,
                    trailing = {
                        Text(
                            text = "${storageKb} KB",
                            style = loomType.monoStat,
                            color = c.textMuted,
                        )
                    },
                )
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { clearOpen = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconTrash, contentDescription = null, tint = c.error, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Clear all Loom data", style = loomType.bodyStrong, color = c.error)
                        Text(
                            text = "Projects, settings and permissions",
                            style = loomType.small,
                            color = c.textMuted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("About")
            LoomCard(contentPadding = PaddingValues(14.dp)) {
                AboutRow("Loom", "0.4.0 · prototype")
                AboutRow("Design language", "Loom — warm graphite + signal amber")
                // The prototype ships the repo name of its own demo; this
                // build points at the real repository instead.
                AboutRow("Repository", "testplay-byte/TESTMESH")
                AboutRow("Workspace", workspacePath.ifEmpty { "app storage" })
            }
            Spacer(Modifier.height(10.dp))
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            LoomBottomNav(active = LoomTab.SETTINGS, onSelect = {
                if (it == LoomTab.PROJECTS) onBack()
            })
        }
    }

    LoomSheet(open = clearOpen, onClose = { clearOpen = false }, title = "Clear all Loom data?") {
        Text(
            text = "Removes all local Loom data — projects, settings and permissions.",
            style = loomType.small,
            color = c.textMuted,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LoomButton(
                onClick = {
                    clearOpen = false
                    scope.launch {
                        vm.repository.clearAllData()
                        vm.toast("Local data cleared", ToastIcon.CHECK)
                    }
                },
                fullWidth = true,
                variant = LoomButtonVariant.DANGER,
                label = "Clear data",
            )
            LoomButton(
                onClick = { clearOpen = false },
                fullWidth = true,
                variant = LoomButtonVariant.GHOST,
                label = "Cancel",
            )
        }
    }

    @Suppress("UNUSED_EXPRESSION") LoomButtonSize.MD
}

@Composable
private fun Divider() {
    val c = loomColors
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
}

@Composable
private fun AboutRow(label: String, value: String) {
    val c = loomColors
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = loomType.small, color = c.textMuted, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = loomType.monoSmall,
            color = c.text,
        )
    }
}
