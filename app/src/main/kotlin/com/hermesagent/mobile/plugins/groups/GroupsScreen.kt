package com.hermesagent.mobile.plugins.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.OverlayScaffold
import com.hermesagent.mobile.ui.common.*
import com.hermesagent.mobile.ui.theme.HermesTheme

class GroupsActions(
    val open: (String) -> Unit = {}, val close: () -> Unit = {}, val retry: () -> Unit = {},
    val foreground: (Boolean) -> Unit = {},
)

/** Read-only hosted projection; Desktop source and deliberate drift are ledgered in bot-group-chat.md. */
@Composable
fun GroupsScreen(
    state: GroupsUiState,
    onBack: () -> Unit,
    actions: GroupsActions = GroupsActions(),
    profiles: List<com.hermesagent.mobile.data.profiles.HermesProfile> = emptyList(),
) {
    LifecycleResumeEffect(actions) {
        actions.foreground(true)
        onPauseOrDispose { actions.foreground(false) }
    }
    val back = if (state.selected == null) onBack else actions.close
    BackHandler(state.selected != null) { actions.close() }
    OverlayScaffold(title = state.transcript?.state?.room?.name ?: "Group Chats", onBack = back) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (state.stale) GroupText("Could not refresh. Showing the last saved view.")
            if (state.authorityConflict || state.transcript?.authorityLost == true) GroupText("Managed by another Gateway. Read only.")
            if (state.expired) {
                GroupText("This Group Chat’s history is no longer available.")
            } else if (state.phase == GroupsPhase.Unsupported) {
                GroupText("Group Chats need a newer Gateway. Update Hermes and reconnect.")
            } else if (state.selected != null) {
                if (state.roomLoading) GroupText("Loading Group Chat…")
                if (state.phase == GroupsPhase.Failure) ReadFailure(actions.retry)
                state.transcript?.let { transcript ->
                    val room = transcript.state.room
                    val matched = room.copy(members = room.members.map { member ->
                        val profile = profiles.firstOrNull { !member.peer && it.name == member.profile }
                        member.copy(label = profile?.displayName?.takeIf { it.isNotBlank() }?.let(::redact) ?: member.label)
                    })
                    GroupRoom(transcript.copy(state = transcript.state.copy(room = matched)))
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GroupText("Group Chats", Modifier.weight(1f))
                    ComingSoonIconAction(HermesIcon.Add, "New group chat")
                }
                when (state.phase) {
                    GroupsPhase.Loading -> GroupText("Loading Group Chats…")
                    GroupsPhase.Failure -> ReadFailure(actions.retry)
                    else -> if (state.rooms.isEmpty()) GroupText("No Group Chats yet.")
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rooms, key = { it.id }) { room -> GroupRow(room, actions.open) }
                }
            }
        }
    }
}

@Composable
private fun ReadFailure(retry: () -> Unit) {
    GroupText("Could not load Group Chats. Try again.")
    PrimaryButton(label = "Retry", onClick = retry)
}

@Composable
private fun GroupRow(room: HostedGroup, open: (String) -> Unit) {
    var menu by remember(room.id) { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { open(room.id) }.padding(vertical = 8.dp)) {
                GroupText(room.name)
                GroupText("${room.members.size} bots")
            }
            Box {
                HermesIconButton(HermesIcon.KebabVertical, "Group Chat actions", onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { GroupText("Open Group Chat") }, onClick = { menu = false; open(room.id) })
                    Hairline()
                    DropdownMenuItem(text = { ComingSoonAction("Pin to top") }, enabled = false, onClick = {})
                    DropdownMenuItem(text = { ComingSoonAction("Move to section") }, enabled = false, onClick = {})
                    Hairline()
                    DropdownMenuItem(text = { ComingSoonAction("Delete") }, enabled = false, onClick = {})
                }
            }
            Column {
                ComingSoonIconAction(HermesIcon.ChevronUp, "Move up")
                ComingSoonIconAction(HermesIcon.ChevronDown, "Move down")
            }
        }
        Hairline()
    }
}

@Composable
private fun ColumnScope.GroupRoom(transcript: GroupTranscript) {
    val state = transcript.state
    val room = state.room
    FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        GroupText("${room.members.size} bots", Modifier.align(Alignment.CenterVertically))
        ComingSoonIconAction(HermesIcon.SettingsGear, "Group settings for ${room.name}")
        ComingSoonIconAction(HermesIcon.Organization, "Manage group members")
        ComingSoonIconAction(HermesIcon.Trash, "Disband ${room.name}")
    }
    GroupText(when {
        room.disbanded -> "Disbanded"
        state.blocked -> "A bot in this group chat needs your input"
        state.working -> "The room is working…"
        else -> "Activity"
    })
    Row {
        ComingSoonAction("Stop")
        state.pending.distinct().forEach { ComingSoonAction(it) }
    }
    LazyColumn(Modifier.weight(1f).testTag("Group transcript")) {
        items(room.members, key = { "member:${it.id}" }) { member ->
            Row { GroupText(member.label); if (member.peer) ComingSoonAction("Peer member") }
        }
        items(state.peerRoutes) { ComingSoonAction(it) }
        items(transcript.events, key = { "event:${it.seq}" }) { event ->
            val label = if (event.kind == "message.user") "You"
                else room.members.firstOrNull { it.id == event.memberId }?.label
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).then(
                if (event.kind == "message.user") Modifier.background(HermesTheme.tokens.cardSurface, RoundedCornerShape(8.dp)).padding(10.dp)
                else Modifier,
            )) {
                label?.let { GroupText(it) }
                GroupText(event.text)
                if (event.kind.startsWith("message.")) ComingSoonAction("Reply in thread")
            }
        }
    }
    Hairline()
    // Desktop's composer remains visible, but has no editable field or submit callback in this slice.
    GroupText("Say something — every bot in this group hears the room.")
    Row {
        ComingSoonIconAction(HermesIcon.Attach, "Attach files — every responding bot sees them")
        ComingSoonAction("New Thread")
    }
}

@Composable
private fun GroupText(text: String, modifier: Modifier = Modifier) {
    Text(redact(text), modifier = modifier, color = HermesTheme.tokens.textPrimary, style = HermesTheme.type.body)
}
