package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.groups.*
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*

/** Debug-only synthetic wire data driving the production repository, state holder and screen. */
class GroupsParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val captureState = intent.getStringExtra("visual_parity_state")
        require(captureState in setOf("populated", "read-failure"))
        val repository = GroupsRepository({ method, params ->
            if (captureState == "read-failure" && method == "groups.list") {
                PluginHostResult.Refused(4114, "Hermes refused that Gateway request.")
            } else PluginHostResult.Success(Json.parseToJsonElement(when (method) {
                "groups.capabilities" -> CAPABILITIES
                "groups.list" -> """{"rooms":[$ROOM],"next_offset":null}"""
                "groups.state" -> """{"room":$ROOM}"""
                "groups.log" -> if (params["since_seq"]?.jsonPrimitive?.long == 1L)
                    """{"events":[],"cursor":1,"latest_seq":1,"has_more":false,"authority":{"gateway_id":"synthetic","epoch":1}}"""
                    else LOG
                else -> error("Unexpected fixture request")
            }))
        })
        val model = GroupsViewModel(lifecycleScope, MutableStateFlow(GroupReadConnection(repository) { true }), MutableStateFlow(0L))
        val actions = GroupsActions(model::open, model::closeRoom, model::refresh, model::setForeground)
        val mode = if (intent.getStringExtra("visual_parity_theme") == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent {
            val state by model.uiState.collectAsState()
            HermesTheme(AppearanceSelection("mono", mode)) {
                GroupsScreen(state, onBack = { finish() }, actions = actions)
            }
        }
    }

    private companion object {
        const val CAPABILITIES = """{"protocol_version":2,"driver":true,"persistent_process":true,
            "authority_gateway_id":"synthetic","room_link":{"enabled":false},"features":[],
            "methods":["groups.capabilities","groups.list","groups.state","groups.log"],"max_log_limit":500}"""
        const val ROOM = """{"room_id":"synthetic-room","name":"Planning","members":[
            {"member_id":"ops","profile":"ops","handle":"ops","display_name":"Ops","target":{"kind":"local","profile":"ops"}},
            {"member_id":"review","profile":"review","handle":"review","display_name":"Review","target":{"kind":"local","profile":"review"}}],
            "authority_gateway_id":"synthetic","authority_epoch":1,"revision":1,"created_at":1.0,"updated_at":1.0,
            "idempotent":false,"latest_seq":1}"""
        const val LOG = """{"events":[{"room_id":"synthetic-room","seq":1,"event_id":"synthetic-event","kind":"message.user",
            "actor":{"kind":"user","id":"desktop"},"authority_epoch":null,"payload":{"text":"Review the release plan.","thread_id":"main"},
            "created_at":1.0,"idempotent":false}],"cursor":1,"latest_seq":1,"has_more":false,"authority":{"gateway_id":"synthetic","epoch":1}}"""
    }
}
