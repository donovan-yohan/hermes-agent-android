package com.hermesagent.mobile.plugins.bots

import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.profiles.AndroidAvatarImporter
import com.hermesagent.mobile.data.profiles.AndroidAvatarDecoder
import com.hermesagent.mobile.ui.common.ComingSoonAction
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.launch

class BotAvatarActions(
    val onBeginPick: () -> BotAvatarPick? = { null },
    val onPicked: (BotAvatarPick, ByteArray?) -> Unit = { _, _ -> },
    val onPickError: (BotAvatarPick) -> Unit = {},
    val onClear: () -> Unit = {}, val onSave: () -> Unit = {},
)
fun botAvatarActions(vm: BotsAvatarViewModel, snapshot: BotAvatarState) = BotAvatarActions(
    onBeginPick = { vm.beginPick(snapshot) }, onPicked = vm::finishPick, onPickError = vm::failPick,
    onClear = { vm.clear(snapshot) }, onSave = { vm.save(snapshot) },
)
@Composable
fun BotAvatarEditor(state: BotAvatarState, actions: BotAvatarActions, chooseImage: ((BotAvatarPick) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(actions)
    var activePick by remember { mutableStateOf<BotAvatarPick?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val ticket = activePick
        if (ticket != null) {
            if (uri == null) { activePick = null; latest.onPicked(ticket, null) }
            else if (uri.scheme != "content") { activePick = null; latest.onPickError(ticket) }
            else scope.launch {
                val png = AndroidAvatarImporter().normalize { context.contentResolver.openInputStream(uri) }
                if (activePick == ticket) activePick = null
                if (png == null) latest.onPickError(ticket) else latest.onPicked(ticket, png)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { activePick?.let { latest.onPicked(it, null) } }
    }
    val preview by produceState<android.graphics.Bitmap?>(null, state.ticket, state.bytes) {
        value = null
        state.bytes?.let { value = AndroidAvatarDecoder().decode(it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Avatar", style = HermesTheme.type.sectionLabel)
        preview?.let { Image(it.asImageBitmap(), "Avatar preview", Modifier.size(96.dp).clip(BOT_AVATAR_SHAPE)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComingSoonAction("Bot", Modifier.semantics { role = Role.Button })
            ComingSoonAction("Generate", Modifier.semantics { role = Role.Button })
        }
        TextButton("Upload", modifier = Modifier.semantics { role = Role.Button }, enabled = state.editable, onClick = {
            actions.onBeginPick()?.let { ticket ->
                if (chooseImage != null) chooseImage(ticket)
                else {
                    activePick = ticket
                    try { launcher.launch("image/*") }
                    catch (_: Exception) { activePick = null; latest.onPickError(ticket) }
                }
            }
        })
        ComingSoonAction("Pet", Modifier.semantics { role = Role.Button })
        if (state.bytes != null) TextButton("Remove image", actions.onClear, modifier = Modifier.semantics { role = Role.Button }, enabled = state.editable)
        Text("Choose an image from this device. It will be cropped to a square.",
            style = HermesTheme.type.caption, color = HermesTheme.tokens.textSecondary)
        when {
            state.loading -> Text("Loading avatar…", style = HermesTheme.type.caption)
            state.picking != null -> Text("Choosing image…", style = HermesTheme.type.caption)
            state.busy -> Text("Saving avatar…", style = HermesTheme.type.caption)
        }
        state.message?.let { Text(it, style = HermesTheme.type.caption, color = HermesTheme.tokens.textSecondary) }
        TextButton("Save avatar", actions.onSave, modifier = Modifier.semantics { role = Role.Button }, enabled = state.canSave)
    }
}
