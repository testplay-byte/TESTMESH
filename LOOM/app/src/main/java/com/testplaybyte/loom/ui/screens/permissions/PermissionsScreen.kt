package com.testplaybyte.loom.ui.screens.permissions

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.domain.model.ToastIcon
import com.testplaybyte.loom.ui.LoomViewModel
import com.testplaybyte.loom.ui.components.LoomButton
import com.testplaybyte.loom.ui.components.LoomButtonSize
import com.testplaybyte.loom.ui.components.LoomButtonVariant
import com.testplaybyte.loom.ui.components.LoomCard
import com.testplaybyte.loom.ui.components.LoomMark
import com.testplaybyte.loom.ui.icons.IconCheck
import com.testplaybyte.loom.ui.icons.IconFolderOpen
import com.testplaybyte.loom.ui.icons.IconPhotos
import com.testplaybyte.loom.ui.icons.IconShield
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlinx.coroutines.launch

/**
 * §2 Permissions (first run) — real Android grants (docs/03 §2 +
 * docs/06 §8).
 *
 *  · `Photos & videos` → the real READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE
 *    runtime permission;
 *  · `All files` → SAF document tree (never MANAGE_EXTERNAL_STORAGE): the
 *    card is satisfied by picking the workspace folder (sets files+folder);
 *  · `Storage folder` → ACTION_OPEN_DOCUMENT_TREE, persisted, and the
 *    card's sub becomes the chosen path (mono).
 *
 * Continue unlocks only when ALL THREE are granted; "Continue with limited
 * access" is always available and runs the app on app-private storage.
 */
@Composable
fun PermissionsScreen(
    vm: LoomViewModel,
    onDone: () -> Unit,
) {
    val c = loomColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val perms by vm.perms.collectAsState()
    val workspacePath by vm.workspacePath.collectAsState()

    // Real media permission request.
    val mediaPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch {
                vm.repository.grantPerm("media")
                vm.toast("Permission granted", ToastIcon.CHECK)
            }
        } else {
            vm.toast("Photos access was denied", ToastIcon.WARN)
        }
    }

    // Real SAF folder picker (grants "All files" + "Storage folder").
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
                // Some providers do not offer persistable grants; the app
                // still works for this session and falls back on restart.
            }
            val name = folderDisplayName(uri) ?: "LoomDatasets"
            scope.launch {
                vm.repository.setFolder(uri.toString(), name)
                vm.toast("Permission granted", ToastIcon.CHECK)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(c.surface2),
            contentAlignment = Alignment.Center,
        ) {
            LoomMark(size = 30.dp, strokeWidth = 1.6f, tint = c.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Set up Loom",
            style = loomType.screenTitle,
            color = c.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Loom needs access to your files to store datasets",
            style = loomType.small.copy(fontSize = 13.sp),
            color = c.textMuted,
        )
        Spacer(Modifier.height(22.dp))

        GrantCard(
            icon = IconPhotos,
            title = "Photos & videos",
            sub = "Read the images you annotate",
            granted = perms.media,
            onGrant = { mediaLauncher.launch(mediaPermission) },
        )
        Spacer(Modifier.height(12.dp))
        GrantCard(
            icon = IconFolderOpen,
            title = "All files",
            sub = "Create dataset folders and write exports",
            granted = perms.files,
            onGrant = { folderLauncher.launch(null) },
        )
        Spacer(Modifier.height(12.dp))
        GrantCard(
            icon = IconShield,
            title = "Storage folder",
            sub = if (perms.folder && perms.folderName != null) {
                workspacePath.ifEmpty { perms.folderName ?: "" }
            } else {
                "Pick where LoomDatasets lives"
            },
            subIsMono = perms.folder,
            granted = perms.folder,
            onGrant = { folderLauncher.launch(null) },
        )

        Spacer(Modifier.height(26.dp))
        LoomButton(
            onClick = onDone,
            fullWidth = true,
            enabled = perms.allGranted,
            label = "Continue",
        )
        if (!perms.allGranted) {
            Spacer(Modifier.height(8.dp))
            LoomButton(
                onClick = {
                    scope.launch {
                        vm.repository.skipPerms()
                        onDone()
                    }
                },
                fullWidth = true,
                variant = LoomButtonVariant.GHOST,
                label = "Continue with limited access",
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Your grants persist; you can change them in system settings.",
            style = loomType.small,
            color = c.textMuted,
            modifier = Modifier.padding(bottom = 26.dp),
        )
    }
}

/** One grant card: 20dp icon in a 32dp circle, title + sub, trailing state. */
@Composable
private fun GrantCard(
    icon: ImageVector,
    title: String,
    sub: String,
    granted: Boolean,
    onGrant: () -> Unit,
    subIsMono: Boolean = false,
) {
    val c = loomColors
    LoomCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(c.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = loomType.title, color = c.text)
                Text(
                    text = sub,
                    style = if (subIsMono) loomType.monoSmall else loomType.small,
                    color = c.textMuted,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        IconCheck, contentDescription = null,
                        tint = c.pos, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Allowed", style = loomType.smallStrong, color = c.text)
                }
            } else {
                LoomButton(
                    onClick = onGrant,
                    size = LoomButtonSize.SM,
                    variant = LoomButtonVariant.TONAL,
                    label = "Grant",
                )
            }
        }
    }
}

/** Display name of a picked tree (last path segment of its document id). */
private fun folderDisplayName(uri: Uri): String? = try {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { null }
} catch (e: Exception) {
    uri.lastPathSegment
}
