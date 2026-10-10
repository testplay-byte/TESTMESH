package com.testplaybyte.loom.ui.nav

/**
 * Navigation graph — the Android mapping of the prototype's hash routes
 * (docs/04 §1). Navigation Compose, single activity.
 */
object Routes {
    const val SPLASH = "splash"
    const val PERMISSIONS = "permissions"
    const val PROJECTS = "projects"
    const val NEW_PROJECT = "new"
    const val SETTINGS = "settings"

    /** `projects/{id}` */
    const val PROJECT = "projects/{projectId}"
    fun project(id: String) = "projects/$id"

    /** `projects/{id}/annotate/{index}` */
    const val ANNOTATE = "projects/{projectId}/annotate/{index}"
    fun annotate(id: String, index: Int) = "projects/$id/annotate/$index"

    /** `projects/{id}/export` */
    const val EXPORT = "projects/{projectId}/export"
    fun export(id: String) = "projects/$id/export"

    const val ARG_PROJECT_ID = "projectId"
    const val ARG_INDEX = "index"
}
