package com.testplaybyte.loom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.testplaybyte.loom.LoomApp
import com.testplaybyte.loom.data.repo.LoomRepository
import com.testplaybyte.loom.domain.model.LoomSettings
import com.testplaybyte.loom.domain.model.Perms
import com.testplaybyte.loom.domain.model.Project
import com.testplaybyte.loom.domain.model.ToastMsg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app's single ViewModel — a thin, observable facade over
 * [LoomRepository] plus the toast slot (docs/06 §3: "LoomViewModel per
 * screen + a shared LoomRepository"; this app's screens are simple enough
 * that one shared VM with per-screen local state is the honest
 * simplification, and every action still funnels through the repository).
 *
 * Exposes:
 *  · repository flows (settings/perms/projects/hydrated/workspace path);
 *  · [toast] — the one-at-a-time message slot (2600ms auto-clear);
 *  · [actions] passthrough so composables never touch data classes
 *    directly.
 */
class LoomViewModel(private val app: LoomApp) : ViewModel() {

    val repository: LoomRepository get() = app.repository

    val settings: StateFlow<LoomSettings> = repository.settings
    val perms: StateFlow<Perms> = repository.perms
    val projects: StateFlow<List<Project>> = repository.projects
    val hydrated: StateFlow<Boolean> = repository.hydrated
    val workspacePath: StateFlow<String> = repository.workspacePath

    private val _toast = MutableStateFlow<ToastMsg?>(null)
    val toast: StateFlow<ToastMsg?> = _toast.asStateFlow()

    init {
        // Pipe repository toasts into the single slot with the 2600ms life.
        viewModelScope.launch {
            repository.toasts.collect { msg -> showToast(msg) }
        }
    }

    fun showToast(msg: ToastMsg) {
        _toast.value = msg
        viewModelScope.launch {
            kotlinx.coroutines.delay(com.testplaybyte.loom.ui.theme.LoomMotion.TOAST_LIFETIME_MS)
            if (_toast.value?.id == msg.id) _toast.value = null
        }
    }

    fun toast(text: String, icon: com.testplaybyte.loom.domain.model.ToastIcon =
        com.testplaybyte.loom.domain.model.ToastIcon.INFO) =
        repository.toast(text, icon)

    fun dismissToast() {
        _toast.value = null
    }

    fun projectById(id: String?): Project? = repository.projectById(id)

    override fun onCleared() {
        super.onCleared()
    }

    /** Factory so the VM can take the Application (manual DI, no Hilt). */
    class Factory(private val app: LoomApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LoomViewModel(app) as T
    }
}
