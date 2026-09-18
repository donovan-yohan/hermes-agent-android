package com.hermesagent.mobile.data.profiles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/**
 * Memory-only, bounded read core. Methods are thread-safe; source liveness checks must
 * be synchronous, nonblocking and must not call back into this repository.
 *
 * The owner must call [activate] synchronously on connection changes (before publishing
 * new rows), [refresh] on foreground/explicit refresh, and close subscriptions on disposal.
 * Old rows retain their captured source and cannot acquire against its replacement.
 * [Subscription.current] also fences a transport change before its lifecycle callback runs.
 *
 * A single LRU holds at most 64 entries, including work, misses and error suppression.
 * At most 256 subscribers and two RPC/decode operations are admitted. Retained bitmaps
 * are allocation-accounted; pressure may evict an observed image to fallback until refresh.
 * Images already held by callers are outside cache accounting and are never recycled here.
 */
internal class ProfileAvatarRepository(
    private val scope: CoroutineScope,
    private val decoder: AvatarDecoder = AndroidAvatarDecoder(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private class Entry(val name: String, val source: CapturedAvatarSource) {
        val state = MutableStateFlow<ProfileAvatar>(ProfileAvatar.Loading)
        var expires = 0L
        var refs = 0
        var allowed = true
        var task: Task? = null
    }
    private class Task { lateinit var job: Job }
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val permits = Semaphore(AvatarLimits.WORKERS)
    private var source: CapturedAvatarSource? = null
    private var closed = false

    internal class Subscription(
        val updates: StateFlow<ProfileAvatar>,
        private val snapshot: () -> ProfileAvatar,
        private val dispose: () -> Unit,
    ) : AutoCloseable {
        /** Read at rendering time, not a remembered value from a previous owner. */
        fun current(): ProfileAvatar = snapshot()
        override fun close() = dispose()
    }

    /** Also fences same-endpoint reconnects. Conservative policy: discard their cache. */
    fun activate(captured: CapturedAvatarSource?) = synchronized(lock) {
        if (closed || source === captured) return@synchronized
        clear()
        source = captured
    }

    fun subscribe(captured: CapturedAvatarSource, rawName: String, hasAvatar: Boolean): Subscription =
        synchronized(lock) {
            if (!live(captured)) return@synchronized fallback()
            // False is authoritative even when subscription/work admission is exhausted.
            if (!hasAvatar) {
                entries[rawName]?.let { entry ->
                    stop(entry)
                    entry.allowed = false
                    entry.state.value = ProfileAvatar.Missing
                    entry.expires = Long.MAX_VALUE
                }
                return@synchronized fallback(ProfileAvatar.Missing)
            }
            if (entries.values.sumOf { it.refs } >= AvatarLimits.SUBSCRIBERS) return@synchronized fallback()
            var entry = entries[rawName]
            if (entry == null) {
                if (entries.size >= AvatarLimits.ENTRIES) {
                    val victim = entries.values.firstOrNull { it.refs == 0 && it.task == null }
                        ?: return@synchronized fallback()
                    entries.remove(victim.name)
                }
                entry = Entry(rawName, captured)
                entries[rawName] = entry
            }
            val selected = entry
            selected.refs++
            if (!selected.allowed) {
                selected.allowed = true
                selected.expires = 0
                selected.state.value = ProfileAvatar.Loading
            }
            start(selected)
            var disposed = false
            Subscription(selected.state.asStateFlow(), {
                synchronized(lock) {
                    if (!disposed && live(captured) && entries[rawName] === selected) selected.state.value
                    else ProfileAvatar.Unavailable
                }
            }, {
                synchronized(lock) {
                    if (!disposed) {
                        disposed = true
                        selected.refs--
                        if (selected.refs == 0) stop(selected)
                    }
                }
            })
        }

    /** Null name invalidates all current keys; bypasses both TTL and error suppression. */
    fun refresh(captured: CapturedAvatarSource, rawName: String? = null) = synchronized(lock) {
        if (!live(captured)) return@synchronized
        entries.values.filter { rawName == null || it.name == rawName }.forEach { entry ->
            stop(entry)
            if (entry.allowed) {
                entry.expires = 0
                entry.state.value = ProfileAvatar.Loading
                start(entry)
            }
        }
    }

    private fun live(captured: CapturedAvatarSource) =
        !closed && scope.isActive && source === captured && captured.isCurrent()

    private fun start(entry: Entry) {
        if (entry.refs == 0 || !entry.allowed || entry.task != null || !live(entry.source)) return
        // Suppression is distinct from Missing; re-subscription cannot bypass it.
        if (entry.state.value == ProfileAvatar.Unavailable && now() < entry.expires) return
        val task = Task()
        task.job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            while (true) {
                val wait = synchronized(lock) { (entry.expires - now()).coerceAtLeast(0) }
                if (wait > 0) delay(wait)
                val result = permits.withPermit {
                    if (!synchronized(lock) { valid(entry, task) }) return@launch
                    fetch(entry)
                }
                coroutineContext.ensureActive()
                val keepRefreshing = synchronized(lock) {
                    if (!valid(entry, task)) return@synchronized false
                    entry.state.value = result
                    entry.expires = now() + if (result == ProfileAvatar.Unavailable) AvatarLimits.ERROR_MS else AvatarLimits.TTL_MS
                    trimImages()
                    result != ProfileAvatar.Unavailable && entry.refs > 0
                }
                if (!keepRefreshing) break
            }
        }
        entry.task = task
        task.job.invokeOnCompletion {
            synchronized(lock) {
                // Also runs for cancellation before dispatch; never removes a replacement.
                if (entry.task === task) entry.task = null
            }
        }
        task.job.start()
    }

    private fun valid(entry: Entry, task: Task) = live(entry.source) && entry.allowed &&
        entries[entry.name] === entry && entry.task === task && entry.refs > 0

    private suspend fun fetch(entry: Entry): ProfileAvatar = try {
        val payload = parseAvatarPayload(entry.source.getAvatar(entry.name))
        coroutineContext.ensureActive()
        // Recheck before spending native decode resources on an obsolete reply.
        if (!entry.source.isCurrent()) ProfileAvatar.Unavailable else when (payload) {
            AvatarPayload.Missing -> ProfileAvatar.Missing
            is AvatarPayload.Raster -> decoder.decode(payload.bytes)?.takeIf(::isBoundedAvatar)
                ?.let(ProfileAvatar::Ready) ?: ProfileAvatar.Unavailable
            null -> ProfileAvatar.Unavailable
        }
    } catch (_: TimeoutCancellationException) {
        // A transport-owned deadline is a retry-suppressed error, not waiter disposal.
        // Cancellation of this repository job must still propagate.
        coroutineContext.ensureActive()
        ProfileAvatar.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Backend prose, identifiers and encoded material never escape into state or logs.
        ProfileAvatar.Unavailable
    }

    private fun trimImages() {
        var bytes = entries.values.sumOf { (it.state.value as? ProfileAvatar.Ready)?.bitmap?.allocationByteCount ?: 0 }
        for (entry in entries.values) {
            if (bytes <= AvatarLimits.CACHE_BYTES) break
            val image = entry.state.value as? ProfileAvatar.Ready ?: continue
            bytes -= image.bitmap.allocationByteCount
            entry.state.value = ProfileAvatar.Unavailable
            // Do not retry from recomposition under cache pressure.
        }
    }

    private fun stop(entry: Entry) {
        val previous = entry.task
        entry.task = null
        previous?.job?.cancel()
    }

    private fun clear() {
        entries.values.forEach { stop(it); it.state.value = ProfileAvatar.Unavailable }
        entries.clear()
    }

    override fun close() = synchronized(lock) {
        closed = true
        clear()
        source = null
    }

    private fun fallback(value: ProfileAvatar = ProfileAvatar.Unavailable) =
        Subscription(MutableStateFlow(value).asStateFlow(), { value }, {})
}
