/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.runtime.state

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The state of an aggregate after its stream [version] (design 01 §2, 08 §5): its [state] as the kernel's codec
 * serialized it, the [scope] of the stream, whether it is [deleted], its [tags] and its [schedules] (decision D198),
 * as the kernel committed them.
 * A view is serialized once and decoded afresh by each reader, so no reader sees another's changes nor the live
 * instance. This is the runtime's form, the text a snapshot stores; what a sink publishes is the `:spi`
 * [me.ahoo.wow.spi.state.StateView] of design 08 §5 (decision D132), a JSON tree with its schema's fingerprint, which
 * [me.ahoo.wow.runtime.sink.SinkSubscription] maps this one to.
 */
@InternalWowApi
class SerializedState(
    val version: Int,
    val state: String,
    val scope: Scope = Scope.NONE,
    val deleted: Boolean = false,
    val tags: Map<String, List<String>> = emptyMap(),
    val schedules: Map<String, ScheduleRule> = emptyMap(),
) {
    init {
        require(version >= 1) { "A committed state has a version from 1." }
    }

    override fun toString(): String = "SerializedState(version=$version)"
}

/** The bounds of a context's [StateViews]: at most [capacity] views, none older than [maxAge]. */
data class StateViewOptions(
    val capacity: Int = DEFAULT_CAPACITY,
    val maxAge: Duration = DEFAULT_MAX_AGE,
) {
    init {
        require(capacity >= 1) { "The state views hold at least one view." }
        require(maxAge.isPositive()) { "The maximum age of a state view is positive." }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 4_096
        val DEFAULT_MAX_AGE: Duration = 1.minutes
    }
}

/**
 * The state views of one bounded context, shared by every state consumer of it (design 04 §1, decision D133): the
 * snapshot subscription, the subscriptions of annotated processors, and later the sinks. Each of them is a named
 * [readers] entry with the aggregate types it reads.
 *
 * - **Recording**: the executor [record]s the state after each commit of an aggregate type some reader reads, while
 *   the aggregate's lane still holds it; a reader that had to rebuild a state ([StateViewReader]) [put]s it here too,
 *   so the other readers, and its own next entry of the aggregate, start from it.
 * - **Reading**: a view is used only for the exact version it was taken at ([get]), never taken away: every reader
 *   gets it. [floor] gives the latest one before a version, which a reader behind it evolves with the log's tail.
 * - **Eviction**: a reader [pass]es an aggregate's version once it is done with it. A view is evicted once every
 *   reader of its type passed a later version of its aggregate, so the latest view each reader handled stays as the
 *   base of its next entry; and in any case once it is older than [StateViewOptions.maxAge] or the views exceed
 *   [StateViewOptions.capacity], oldest first: a reader that runs elsewhere (another node owns the partition) or is
 *   parked never holds views for long. A view missing for any reason costs its reader a rebuild, never a wrong state.
 */
@InternalWowApi
class StateViews(
    private val codec: EventCodec,
    readers: Map<String, Set<AggregateType>>,
    private val options: StateViewOptions = StateViewOptions(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** The readers, by name, with the aggregate types each reads. */
    val readers: Map<String, Set<AggregateType>> = readers.mapValues { it.value.toSet() }

    private val readersOf: Map<AggregateType, Set<String>> = buildMap<AggregateType, MutableSet<String>> {
        this@StateViews.readers.forEach { (reader, types) -> types.forEach { getOrPut(it) { linkedSetOf() } += reader } }
    }

    /** The aggregate types whose views are recorded: those some reader reads. */
    val types: Set<AggregateType> get() = readersOf.keys

    private val lock = ReentrantLock()
    private val slots = HashMap<AggregateRef, Slot>()

    /** Every view held, in the order it was put: the oldest first. */
    private val order = LinkedHashMap<Key, Entry>()

    /** The number of views held. */
    val size: Int get() = lock.withLock { order.size }

    /** Records the state of [committed], when some reader reads [model]'s aggregate type. */
    fun record(model: AggregateModel<*>, committed: CommandOutcome.Committed) {
        if (model.type !in readersOf) {
            return
        }
        val stream = committed.stream
        put(
            stream.ref,
            SerializedState(
                stream.version,
                codec.stateText(committed.state),
                stream.scope,
                committed.deleted,
                committed.tags,
                committed.schedules,
            ),
        )
    }

    /**
     * Holds [view] of [ref], when some reader of its type may still read it: a view every reader already passed a later
     * version of is not kept, and one already held at its version is kept as it is.
     */
    fun put(ref: AggregateRef, view: SerializedState) {
        val readers = readersOf[ref.type] ?: return
        lock.withLock {
            expire()
            val held = slots[ref]
            if (held != null &&
                (held.views.containsKey(view.version) || readers.all { (held.passed[it] ?: 0) > view.version })
            ) {
                return
            }
            val slot = held ?: Slot().also { slots[ref] = it }
            val entry = Entry(view, timeSource.markNow())
            slot.views[view.version] = entry
            order[Key(ref, view.version)] = entry
            while (order.size > options.capacity) {
                evictEldest()
            }
        }
    }

    /** The view of [ref] taken at [version], or null when there is none at that version. */
    fun get(ref: AggregateRef, version: Int): SerializedState? = lock.withLock {
        expire()
        slots[ref]?.views?.get(version)?.view
    }

    /** The latest view of [ref] before [version], or null when there is none. */
    fun floor(ref: AggregateRef, version: Int): SerializedState? = lock.withLock {
        expire()
        slots[ref]?.views?.lowerEntry(version)?.value?.view
    }

    /**
     * [reader] is done with [ref] up to [version]: the views every reader of the type passed a later version of are
     * evicted. A reader that reads none of the type passes nothing.
     */
    fun pass(reader: String, ref: AggregateRef, version: Int) {
        val readers = readersOf[ref.type] ?: return
        if (reader !in readers) {
            return
        }
        lock.withLock {
            val slot = slots[ref] ?: return
            slot.passed.merge(reader, version, ::maxOf)
            val passed = readers.minOf { slot.passed[it] ?: 0 }
            val evicted = slot.views.headMap(passed, false)
            evicted.keys.forEach { order.remove(Key(ref, it)) }
            evicted.clear()
            if (slot.views.isEmpty()) {
                slots.remove(ref)
            }
        }
    }

    /** Called under the lock: evicts the views older than the maximum age, oldest first. */
    private fun expire() {
        while (order.isNotEmpty() && order.values.first().recorded.elapsedNow() > options.maxAge) {
            evictEldest()
        }
    }

    /** Called under the lock. */
    private fun evictEldest() {
        val eldest = order.keys.first()
        order.remove(eldest)
        val slot = slots[eldest.ref] ?: return
        slot.views.remove(eldest.version)
        if (slot.views.isEmpty()) {
            slots.remove(eldest.ref)
        }
    }

    private data class Key(val ref: AggregateRef, val version: Int)

    private class Entry(val view: SerializedState, val recorded: TimeMark)

    /** The views of one aggregate by version, and the version each reader passed while it had some. */
    private class Slot {
        val views = TreeMap<Int, Entry>()
        val passed = HashMap<String, Int>()
    }
}
