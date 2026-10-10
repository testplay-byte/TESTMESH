package com.testplaybyte.loom

import android.app.Application
import com.testplaybyte.loom.data.prefs.LoomPreferences
import com.testplaybyte.loom.data.repo.LoomRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Loom application object — the manual-DI container (docs/06 §3: "DI:
 * Manual (simple constructor injection) is enough").
 *
 * Owns exactly two singletons: [LoomPreferences] (DataStore) and
 * [LoomRepository] (workspace + project state). Screens reach them through
 * `LoomViewModel`, never directly.
 */
class LoomApp : Application() {

    /** Application-scoped coroutine scope for fire-and-forget boot work. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferences: LoomPreferences by lazy { LoomPreferences(this) }

    val repository: LoomRepository by lazy { LoomRepository(this, preferences) }

    override fun onCreate() {
        super.onCreate()
        // Hydrate settings/perms/workspace/projects as early as possible so
        // the first-run gate (docs/04 §1) can wait on `hydrated`.
        appScope.launch { repository.initialize() }
    }
}
