package com.hermesagent.mobile.data.notifications

import android.graphics.Bitmap
import com.hermesagent.mobile.data.profiles.AvatarRosterCoordinator
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.data.profiles.ProfileAvatar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class NotificationAvatarPresenter(
    private val coordinator: AvatarRosterCoordinator,
    private val roster: () -> List<HermesProfile>,
) {
    /** Synchronous cache-only snapshot for passive activity reconciliation; no new producer. */
    fun current(profile: String?): Bitmap? {
        val row = profile?.let { name -> roster().singleOrNull { it.name == name } }
        val binding = coordinator.bind(row?.avatarRef) ?: return null
        return try { (binding.current() as? ProfileAvatar.Ready)?.bitmap } finally { binding.close() }
    }

    /** Consume only exact accepted local roster rows. Null denotes a peer, never an alias. */
    suspend fun withAvatar(profile: String?, deliver: (Bitmap?) -> Unit) {
        val row = profile?.let { name -> roster().singleOrNull { it.name == name } }
        val binding = coordinator.bind(row?.avatarRef)
        if (binding == null) { deliver(null); return }
        try {
            withTimeoutOrNull(750) { binding.updates.first { it !is ProfileAvatar.Loading } }
            deliver((binding.current() as? ProfileAvatar.Ready)?.bitmap)
        } finally { binding.close() }
    }
}
