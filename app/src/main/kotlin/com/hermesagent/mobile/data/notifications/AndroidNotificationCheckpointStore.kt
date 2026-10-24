package com.hermesagent.mobile.data.notifications

import android.content.Context
import android.util.AtomicFile
import com.hermesagent.mobile.data.ssh.readBounded
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** No credentials, notification body, display name or avatar survives here.
 * Transactions are owned by NotificationInbox.serialization, not by instances. */
internal class AndroidNotificationCheckpointStore(context: Context) : NotificationCheckpointStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "notification-checkpoints-v1")
    private fun file(endpoint: String): AtomicFile {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$key.json"))
    }
    override suspend fun load(endpoint: String): NotificationCheckpoint = withContext(Dispatchers.IO) {
        val source = file(endpoint)
        if (!source.baseFile.exists()) return@withContext NotificationCheckpoint()
        val bytes = source.openRead().use { stream ->
            val data = stream.readBounded(MAX_BYTES + 1)
            require(data.size <= MAX_BYTES)
            data
        }
        val o = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(o.getValue("version").jsonPrimitive.int == 1)
        fun n(key: String) = o.getValue(key).jsonPrimitive.long
        NotificationCheckpoint(
            accepted = o.getValue("accepted").jsonArray.map { it.jsonPrimitive.content }.also { require(it.size <= 4096) },
            enrolledAt = o["enrolledAt"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long,
            sessionSweepCompleted = o["sessionSweepCompleted"]?.jsonPrimitive?.booleanOrNull ?: false,
            roomSweepCompleted = o["roomSweepCompleted"]?.jsonPrimitive?.booleanOrNull ?: false,
            sessions = o.getValue("sessions").jsonObject.mapValues { (_, v) -> v.jsonObject.let {
                NotificationSessionCursor(it.getValue("highwater").jsonPrimitive.long,
                    it.getValue("offset").jsonPrimitive.long, it.getValue("ceiling").jsonPrimitive.long,
                    it["minAt"]?.takeUnless { v -> v == JsonNull }?.jsonPrimitive?.long)
            } },
            rooms = o.getValue("rooms").jsonObject.mapValues { it.value.jsonPrimitive.long },
            roomCutoffs = (o["roomCutoffs"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.long }.orEmpty(),
            sessionOffset = n("sessionOffset"), sessionIndex = n("sessionIndex").toInt(),
            roomOffset = n("roomOffset"), roomIndex = n("roomIndex").toInt(),
        )
    }
    override suspend fun save(endpoint: String, checkpoint: NotificationCheckpoint) = withContext(Dispatchers.IO) {
        val value = buildJsonObject {
            put("version", 1)
            put("sessionSweepCompleted", checkpoint.sessionSweepCompleted)
            put("roomSweepCompleted", checkpoint.roomSweepCompleted)
            put("accepted", JsonArray(checkpoint.accepted.map(::JsonPrimitive)))
            put("enrolledAt", checkpoint.enrolledAt?.let(::JsonPrimitive) ?: JsonNull)
            put("sessions", buildJsonObject { checkpoint.sessions.forEach { (k, v) -> put(k, buildJsonObject {
                put("highwater", v.highwater); put("offset", v.offset); put("ceiling", v.ceiling)
                put("minAt", v.minAt?.let(::JsonPrimitive) ?: JsonNull)
            }) } })
            put("rooms", buildJsonObject { checkpoint.rooms.forEach { (k, v) -> put(k, v) } })
            put("roomCutoffs", buildJsonObject { checkpoint.roomCutoffs.forEach { (k, v) -> put(k, v) } })
            put("sessionOffset", checkpoint.sessionOffset); put("sessionIndex", checkpoint.sessionIndex)
            put("roomOffset", checkpoint.roomOffset); put("roomIndex", checkpoint.roomIndex)
        }.toString().toByteArray()
        require(value.size <= MAX_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        val destination = file(endpoint)
        val stream = destination.startWrite()
        try { stream.write(value); destination.finishWrite(stream) }
        catch (failure: Throwable) { destination.failWrite(stream); throw failure }
    }
    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
