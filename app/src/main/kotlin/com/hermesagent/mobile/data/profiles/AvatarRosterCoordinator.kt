package com.hermesagent.mobile.data.profiles

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Opaque process-local permission. Deliberately neither Serializable nor Parcelable. */
class ProfileAvatarRef internal constructor(
    internal val receipt: AvatarRosterCoordinator.Receipt,
    internal val identity: AvatarRosterCoordinator.Identity,
)

/**
 * Exactly two producer slots, one pending read and accepted receipt per slot, one current
 * metadata projection (64 names), and at most 256 owned subscriptions. No receipt history.
 * All lock ordering is coordinator -> avatar repository; neither source calls back here.
 * UI integration must observe revisions AND connection lifecycle, then use Binding.current().
 */
class AvatarRosterCoordinator internal constructor(
    private val host: PluginHost,
    private val avatars: ProfileAvatarRepository,
) : AutoCloseable {
    internal enum class Kind { Core, Bots }
    internal class Identity(val rawName: String, val hasAvatar: Boolean)
    internal class Receipt(val read: Read, identities: Map<String, Identity>) {
        val refs = identities.mapValues { (_, identity) -> ProfileAvatarRef(this, identity) }
        fun matches(read: Read, identities: Map<String, Identity>): Boolean =
            this.read.source === read.source && this.read.isCurrent() && refs.size == identities.size &&
                refs.all { (raw, ref) -> ref.identity === identities[raw] }
    }
    internal class Read(
        val producer: Producer,
        val order: Long,
        val source: GatewayAvatarAssetSource,
        val requester: GatewayAvatarAssetSource,
    ) {
        suspend fun request(includeSessions: Boolean): PluginHostResult = requester.request(
            "profiles.list", buildJsonObject { put("include_sessions", includeSessions) },
        )
        fun isCurrent() = source.isCurrent() && requester.isCurrent()
    }

    class Producer internal constructor(
        private val coordinator: AvatarRosterCoordinator,
        internal val kind: Kind,
        internal val host: PluginHost,
    ) : AutoCloseable {
        internal var pending: Read? = null
        internal var receipt: Receipt? = null
        internal fun begin(): Read? = coordinator.begin(this)
        internal fun accept(read: Read, result: JsonElement): Map<String, ProfileAvatarRef>? =
            if (read.producer === this) coordinator.accept(read, result) else null
        internal fun invalidate() = coordinator.invalidate(this)
        override fun close() = coordinator.release(this)
    }

    internal inner class Binding(
        private val ref: ProfileAvatarRef,
        private val subscription: ProfileAvatarRepository.Subscription,
    ) : AutoCloseable {
        private var disposed = false
        val updates = subscription.updates
        /** Read-only: safe for a draw guard, with no admission/cache/source mutation. */
        fun current(): ProfileAvatar = synchronized(lock) {
            if (!disposed && eligible(ref)) subscription.current() else ProfileAvatar.Unavailable
        }
        internal fun belongsTo(producer: Producer) = ref.receipt.read.producer === producer
        internal fun isEligible() = eligible(ref)
        override fun close() = synchronized(lock) {
            if (!disposed) {
                disposed = true
                bindings.remove(this)
                subscription.close()
            }
        }
    }

    private val lock = Any()
    private val producers = arrayOfNulls<Producer>(Kind.entries.size)
    private var source: GatewayAvatarAssetSource? = null
    private var identities = emptyMap<String, Identity>()
    private val bindings = mutableSetOf<Binding>()
    private var nextOrder = 0L
    private var acceptedOrder = 0L
    private var closed = false
    private val _revision = MutableStateFlow(0L)
    internal val revision = _revision.asStateFlow()

    internal fun open(kind: Kind, requestHost: PluginHost = host): Producer = synchronized(lock) {
        check(!closed)
        producers[kind.ordinal]?.let(::release)
        Producer(this, kind, requestHost).also { producers[kind.ordinal] = it }
    }

    /** Called by lifecycle/admission, never from drawing. Read-through source checks fence lag. */
    internal fun syncOwner() = synchronized(lock) {
        if (closed || source?.isCurrent() == true) return@synchronized
        resetOwner()
        source = GatewayAvatarAssetSource.capture(host)
        avatars.activate(source)
    }

    /** Synchronous endpoint leave hook; subsequent admission must recapture a ready owner. */
    internal fun reset() = synchronized(lock) { resetOwner() }

    private fun resetOwner() {
        bindings.toList().forEach { it.close() }
        producers.filterNotNull().forEach { it.pending = null; it.receipt = null }
        identities = emptyMap()
        source = null
        avatars.activate(null)
        _revision.value++
    }

    private fun begin(producer: Producer): Read? = synchronized(lock) {
        if (closed || producers[producer.kind.ordinal] !== producer) return@synchronized null
        syncOwner()
        val owner = source ?: return@synchronized null
        val requester = GatewayAvatarAssetSource.capture(producer.host) ?: return@synchronized null
        if (requester.generation != owner.generation || !owner.isCurrent()) return@synchronized null
        Read(producer, ++nextOrder, owner, requester).also { producer.pending = it }
    }

    private fun accept(read: Read, result: JsonElement): Map<String, ProfileAvatarRef>? = synchronized(lock) {
        val producer = read.producer
        if (closed || producers[producer.kind.ordinal] !== producer || producer.pending !== read ||
            source !== read.source || !read.isCurrent()
        ) return@synchronized null
        val rows = (result as? JsonObject)?.get("profiles") as? JsonArray ?: return@synchronized null

        // Keep avatar metadata bounded independently of roster size. Ambiguous duplicate wire names
        // never gain a permit. Overflow rows remain ordinary roster rows without avatar permission.
        val flags = linkedMapOf<String, Boolean>()
        val considered = mutableSetOf<String>()
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val raw = avatarWireName(row) ?: continue
            if (raw in considered) { flags.remove(raw); continue }
            if (considered.size == AvatarLimits.ENTRIES) continue
            considered += raw
            val flag = (row["has_avatar"] as? JsonPrimitive)?.content
            flags[raw] = flag.equals("true", ignoreCase = true) || flag == "1"
        }
        val ownsMetadata = read.order >= acceptedOrder
        if (ownsMetadata) {
            val next = flags.mapValues { (raw, flag) ->
                identities[raw]?.takeIf { it.hasAvatar == flag } ?: Identity(raw, flag)
            }
            // Clear false/removed images before any new row can subscribe.
            (identities.keys - next.keys + next.filterValues { !it.hasAvatar }.keys).forEach {
                avatars.subscribe(read.source, it, false).close()
            }
            identities = next
            acceptedOrder = read.order
        }
        // A same-owner older read may publish primary rows, but can only attach permissions
        // that agree with CURRENT avatar metadata. Null above means stale ownership/read identity
        // and rejects the whole result; an empty map here means valid undecorated primary rows.
        val permitted = identities.filter { (raw, identity) -> flags[raw] == identity.hasAvatar }
        val receipt = producer.receipt?.takeIf { it.matches(read, permitted) } ?: Receipt(read, permitted)
        producer.receipt = receipt
        producer.pending = null
        bindings.filterNot { it.isEligible() }.toList().forEach { it.close() }
        if (ownsMetadata) avatars.refresh(read.source)
        _revision.value++
        receipt.refs
    }

    private fun eligible(ref: ProfileAvatarRef): Boolean {
        val read = ref.receipt.read
        val producer = read.producer
        return !closed && producers[producer.kind.ordinal] === producer && producer.receipt === ref.receipt &&
            source === read.source && read.isCurrent() && identities[ref.identity.rawName] === ref.identity
    }

    /** Null is fallback, not permission to look up the name on a new endpoint. */
    internal fun subscribe(ref: ProfileAvatarRef?): Binding? = synchronized(lock) {
        syncOwner()
        if (ref == null || !eligible(ref) || bindings.size == AvatarLimits.SUBSCRIBERS) return@synchronized null
        Binding(ref, avatars.subscribe(ref.receipt.read.source, ref.identity.rawName, ref.identity.hasAvatar))
            .also { bindings += it }
    }

    private fun invalidate(producer: Producer) = synchronized(lock) {
        producer.pending = null
        producer.receipt = null
        bindings.filter { it.belongsTo(producer) }.toList().forEach { it.close() }
        _revision.value++
    }

    private fun release(producer: Producer) = synchronized(lock) {
        invalidate(producer)
        if (producers[producer.kind.ordinal] === producer) producers[producer.kind.ordinal] = null
    }

    override fun close() = synchronized(lock) {
        closed = true
        resetOwner()
        producers.fill(null)
        avatars.close()
    }
}

/** Preserve established normalized routing fields; asset identity separately keeps the wire string. */
internal fun avatarWireName(row: JsonObject): String? = (row["name"] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
