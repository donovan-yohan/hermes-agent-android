package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.prefs.HiddenProjectsScope

/** UI-only transaction state. Catalog and sessions remain exclusively in SessionCache. */
enum class ProjectActionPage { Menu, Rename, AddFolder, Appearance, Delete }
data class ProjectActionDialog(
    val token: Long,
    val project: ProjectSummary,
    val page: ProjectActionPage = ProjectActionPage.Menu,
    val busy: Boolean = false,
    val error: String? = null,
)
data class ProjectHideUndo(val token: Long, val project: ProjectSummary)
data class ProjectManagementUi(
    val dialog: ProjectActionDialog? = null,
    val hiddenProjects: List<ProjectSummary> = emptyList(),
    val hiddenLoading: Boolean = true,
    val hiddenError: String? = null,
    val hiddenBusy: Boolean = false,
    val undo: ProjectHideUndo? = null,
    val hiddenOwnerToken: Long = 0,
)
internal data class HiddenProjectSnapshot(
    val scope: HiddenProjectsScope? = null,
    val generation: Long = -1,
    val ids: Set<String>? = null,
    val error: String? = null,
    val revision: Long = 0,
)
