package com.testplaybyte.loom.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.testplaybyte.loom.domain.model.ExportFormat
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.LoomSettings
import com.testplaybyte.loom.domain.model.Perms
import com.testplaybyte.loom.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Persisted app preferences: settings, permissions, the workspace tree
 * URI, the demo-seed flag and folders the user deleted (so a folder that
 * only held images is not auto-adopted again).
 *
 * DataStore Preferences — the Android mapping from docs/05 §4 for
 * settings/permissions ("DataStore (Preferences): settings JSON,
 * permissions JSON"). The DATASET DATA itself lives in the workspace
 * folder (the user requirement: that folder is used for everything), not
 * here.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "loom-prefs")

class LoomPreferences(private val context: Context) {

    private object Keys {
        val SETTINGS = stringPreferencesKey("settings-json")
        val PERMS = stringPreferencesKey("perms-json")
        val WORKSPACE_URI = stringPreferencesKey("workspace-tree-uri")
        val SEED_DONE = booleanPreferencesKey("seed-done")
        val DISMISSED = stringSetPreferencesKey("dismissed-folders")
    }

    // ── settings ─────────────────────────────────────────────────────────

    val settings: Flow<LoomSettings> = context.dataStore.data.map { prefs ->
        SettingsCodec.decode(prefs[Keys.SETTINGS])
    }

    suspend fun currentSettings(): LoomSettings = settings.first()

    suspend fun updateSettings(next: LoomSettings) {
        context.dataStore.edit { it[Keys.SETTINGS] = SettingsCodec.encode(next) }
    }

    // ── permissions ──────────────────────────────────────────────────────

    val perms: Flow<Perms> = context.dataStore.data.map { prefs ->
        PermsCodec.decode(prefs[Keys.PERMS])
    }

    suspend fun currentPerms(): Perms = perms.first()

    suspend fun updatePerms(next: Perms) {
        context.dataStore.edit { it[Keys.PERMS] = PermsCodec.encode(next) }
    }

    // ── workspace ────────────────────────────────────────────────────────

    val workspaceUri: Flow<String?> = context.dataStore.data.map { it[Keys.WORKSPACE_URI] }

    suspend fun currentWorkspaceUri(): String? = workspaceUri.first()

    suspend fun setWorkspaceUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.WORKSPACE_URI) else prefs[Keys.WORKSPACE_URI] = uri
        }
    }

    // ── seed + dismissed folders ─────────────────────────────────────────

    val seedDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.SEED_DONE] ?: false }

    suspend fun setSeedDone() {
        context.dataStore.edit { it[Keys.SEED_DONE] = true }
    }

    val dismissedFolders: Flow<Set<String>> = context.dataStore.data.map { it[Keys.DISMISSED] ?: emptySet() }

    suspend fun currentDismissed(): Set<String> = dismissedFolders.first()

    suspend fun addDismissed(folder: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DISMISSED] = (prefs[Keys.DISMISSED] ?: emptySet()) + folder
        }
    }

    /** Clear-all: wipes every Loom preference (docs/03 §8 Data section). */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}

// ── JSON codecs (unit-tested; tolerant on read) ───────────────────────────

object SettingsCodec {
    fun encode(s: LoomSettings): String = JSONObject().apply {
        put("dotSize", s.dotSize)
        put("defaultDensity", s.defaultDensity)
        put("magnifier", s.magnifier)
        put("haptics", s.haptics)
        put("autosave", s.autosave)
        put("undoDepth", s.undoDepth)
        put("defaultFormat", s.defaultFormat.wire)
        put("theme", s.theme.wire)
    }.toString()

    fun decode(text: String?): LoomSettings {
        if (text == null) return LoomSettings.DEFAULT
        return try {
            val o = JSONObject(text)
            LoomSettings(
                dotSize = o.optInt("dotSize", 10).let { if (it in LoomSettings.DOT_SIZES) it else 10 },
                defaultDensity = LoomRules.clampDefaultDensity(o.optInt("defaultDensity", 16)),
                magnifier = o.optBoolean("magnifier", true),
                haptics = o.optBoolean("haptics", true),
                autosave = o.optBoolean("autosave", true),
                undoDepth = LoomRules.clampUndoDepth(o.optInt("undoDepth", 50)),
                defaultFormat = ExportFormat.fromWire(o.optString("defaultFormat", null)),
                theme = ThemeMode.fromWire(o.optString("theme", null)),
            )
        } catch (e: Exception) {
            LoomSettings.DEFAULT
        }
    }
}

object PermsCodec {
    fun encode(p: Perms): String = JSONObject().apply {
        put("media", p.media)
        put("files", p.files)
        put("folder", p.folder)
        put("folderName", p.folderName ?: JSONObject.NULL)
        put("skipped", p.skipped)
    }.toString()

    fun decode(text: String?): Perms {
        if (text == null) return Perms.DEFAULT
        return try {
            val o = JSONObject(text)
            Perms(
                media = o.optBoolean("media", false),
                files = o.optBoolean("files", false),
                folder = o.optBoolean("folder", false),
                folderName = if (o.isNull("folderName")) null else o.optString("folderName", "").ifEmpty { null },
                skipped = o.optBoolean("skipped", false),
            )
        } catch (e: Exception) {
            Perms.DEFAULT
        }
    }
}
