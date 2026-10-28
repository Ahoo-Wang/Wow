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

package me.ahoo.wow.spi.observation

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.result.Stage

/**
 * Something the core noticed that has no duration of its own, told to [Observer.observed]: a refusal, a dropped
 * signal, a partition paused. An observer counts it, logs it or ignores it; the core goes on either way. The core
 * builds each, so their constructors are internal to Wow; an observer matches on the type (`when (event)`) and reads
 * the fields. Types may be added, so a `when` over them keeps an `else` branch.
 *
 * A field from a closed set is an enum whose [value] is its name in metrics and logs (`queue-full`); a field that is
 * open stays text, and its KDoc says why: an error code (an open vocabulary), a node's address, a failure's
 * description.
 */
@WowSpi
sealed class ObservedEvent {
    /** The fields, by name, for [equals], [hashCode] and [toString]. */
    internal abstract fun fields(): List<Pair<String, Any?>>

    final override fun equals(other: Any?): Boolean =
        this === other || other is ObservedEvent && javaClass == other.javaClass && fields() == other.fields()

    final override fun hashCode(): Int = 31 * javaClass.hashCode() + fields().hashCode()

    final override fun toString(): String =
        "${javaClass.simpleName}(${fields().joinToString { (name, value) -> "$name=$value" }})"

    /**
     * A command of [type] that ended before the kernel took it, with the framework or authorization [code] it was
     * answered with: an admission refusal, a busy lane or node (`wow.command.busy`), a closing node, an unavailable
     * store, a wait that timed out while it queued. [code] is text: error codes are an open vocabulary, an
     * application's authorizer's among them.
     */
    class Refused @InternalWowApi constructor(val type: AggregateType, val code: String) : ObservedEvent() {
        override fun fields() = listOf("type" to type, "code" to code)
    }

    /**
     * A command of [commandType] on [type] that the aggregate's decision rejected with [code], an outcome of the
     * business rather than a failure. [withEvents] is whether the rejection carried events; a committed rejection's
     * events are also observed as any commit's. [code] is text: rejection codes are the application's vocabulary.
     */
    class Rejected @InternalWowApi constructor(
        val type: AggregateType,
        val commandType: String,
        val code: String,
        val withEvents: Boolean,
    ) : ObservedEvent() {
        override fun fields() =
            listOf("type" to type, "commandType" to commandType, "code" to code, "withEvents" to withEvents)
    }

    /** A wait on a command of [type] that timed out (`wow.wait.timeout`), at the [stage] it had reached last. */
    class WaitTimedOut @InternalWowApi constructor(val type: AggregateType, val stage: Stage) : ObservedEvent() {
        override fun fields() = listOf("type" to type, "stage" to stage)
    }

    /** A wait signal for the node [origin] (its address, so text) that was dropped without being sent, for [reason]. */
    class SignalDropped @InternalWowApi constructor(val origin: String, val reason: Reason) : ObservedEvent() {
        override fun fields() = listOf("origin" to origin, "reason" to reason)

        /** Why a signal was dropped. */
        enum class Reason(val value: String) {
            /** The origin's queue was full. */
            QUEUE_FULL("queue-full"),

            /** The sender was closing. */
            CLOSED("closed"),

            /** Its wait was over before it was sent. */
            EXPIRED("expired"),

            /** The origin answered with a refusal. */
            REFUSED("refused"),

            /** The send failed and its retries ran out. */
            FAILED("failed"),
        }
    }

    /** A forward of a command of [type] to its partition's owner that did not get the owner's settlement. */
    class ForwardFailed @InternalWowApi constructor(val type: AggregateType, val outcome: Outcome) : ObservedEvent() {
        override fun fields() = listOf("type" to type, "outcome" to outcome)

        /** What became of the command. */
        enum class Outcome(val value: String) {
            /** It surely did not reach the owner, and ran here. */
            LOCAL("local"),

            /** The peer had its most forwards in flight, and it ran here. */
            BUSY("busy"),

            /** It may have reached the owner, and its outcome is unknown. */
            UNKNOWN("unknown"),
        }
    }

    /** A lookup of an aggregate's state in the state cache of [context] by the kernel, or a state evicted from it. */
    class StateCache @InternalWowApi constructor(val context: String, val outcome: Outcome) : ObservedEvent() {
        override fun fields() = listOf("context" to context, "outcome" to outcome)

        /** What the cache did. */
        enum class Outcome(val value: String) {
            /** The state was in the cache. */
            HIT("hit"),

            /** It was not: a miss costs a snapshot and a tail read. */
            MISS("miss"),

            /** A state left the cache. */
            EVICTED("evicted"),
        }
    }

    /**
     * A committed fact of [context] that lost a key it reserved: another owner took the key after the fact's pending
     * reservation expired, so confirming it could not get it back. Logged as an error too; a count to alert on, since
     * the fact now breaks the uniqueness it reserved for.
     */
    class ReservationLost @InternalWowApi constructor(val context: String) : ObservedEvent() {
        override fun fields() = listOf("context" to context)
    }

    /**
     * A store of [context] fell back from an optional [capability] for [reason]; the work went on the way every store
     * can. Counted, never failing.
     */
    class StoreFellBack @InternalWowApi constructor(
        val context: String,
        val capability: Capability,
        val reason: Reason,
    ) : ObservedEvent() {
        override fun fields() = listOf("context" to context, "capability" to capability, "reason" to reason)

        /** The optional store capabilities. */
        enum class Capability(val value: String) {
            /** A store's one-round-trip read of a snapshot with its log's tail ([me.ahoo.wow.spi.log.SnapshotTailReader]). */
            SNAPSHOT_TAIL_READ("snapshot-tail-read"),

            /** A snapshot store's rebuild control plane ([me.ahoo.wow.spi.snapshot.SnapshotRebuildStore]). */
            SNAPSHOT_REBUILD("snapshot-rebuild"),
        }

        /** Why a store fell back. */
        enum class Reason(val value: String) {
            /** The capability failed; the work was done the other way. */
            FAILED("failed"),

            /** The store does not have the capability. */
            UNSUPPORTED("unsupported"),
        }
    }

    /**
     * [subscription] of the subscription [group] paused [partition] on a systemic failure, [reason] describing it (the
     * failure's own words, so text): it handles nothing there but one probe at a time, spending no aggregate's retries
     * and parking none, until a handling succeeds ([PartitionResumed]).
     */
    class PartitionPaused @InternalWowApi constructor(
        val group: String,
        val subscription: String,
        val partition: Int,
        val reason: String,
    ) : ObservedEvent() {
        override fun fields() =
            listOf("group" to group, "subscription" to subscription, "partition" to partition, "reason" to reason)
    }

    /** [subscription] of the subscription [group] resumed [partition], paused for [pausedMillis] ([PartitionPaused]). */
    class PartitionResumed @InternalWowApi constructor(
        val group: String,
        val subscription: String,
        val partition: Int,
        val pausedMillis: Long,
    ) : ObservedEvent() {
        override fun fields() = listOf(
            "group" to group,
            "subscription" to subscription,
            "partition" to partition,
            "pausedMillis" to pausedMillis,
        )
    }

    /**
     * [subscription] of the subscription [group] started throttling [partition] on backpressure ([throttled] true),
     * running fewer handlings at once, or stopped, back at its in-flight bound ([throttled] false).
     */
    class PartitionThrottled @InternalWowApi constructor(
        val group: String,
        val subscription: String,
        val partition: Int,
        val throttled: Boolean,
    ) : ObservedEvent() {
        override fun fields() =
            listOf("group" to group, "subscription" to subscription, "partition" to partition, "throttled" to throttled)
    }

    /**
     * [subscription] of the subscription [group] retries an entry in [partition] whose downstream pushed back with
     * [code] (`wow.command.busy`, `wow.wait.timeout`, `wow.query.busy`, or the failure's class): text, as error codes
     * are an open vocabulary.
     */
    class Backpressured @InternalWowApi constructor(
        val group: String,
        val subscription: String,
        val partition: Int,
        val code: String,
    ) : ObservedEvent() {
        override fun fields() =
            listOf("group" to group, "subscription" to subscription, "partition" to partition, "code" to code)
    }

    /**
     * A tail of [partition] of [context]'s log read the store: the node hands its own commits to its tails in memory,
     * so a tail reads the store only when memory cannot continue it, for [reason].
     */
    class TailRead @InternalWowApi constructor(
        val context: String,
        val partition: Int,
        val reason: Reason,
    ) : ObservedEvent() {
        override fun fields() = listOf("context" to context, "partition" to partition, "reason" to reason)

        /** Why a tail read the store. */
        enum class Reason(val value: String) {
            /** A tail opened behind the head. */
            START("start"),

            /** A sequence another node appended, or whose outcome was unknown. */
            GAP("gap"),

            /** A tail too slow for its hand-overs. */
            OVERFLOW("overflow"),

            /** Entries other nodes appended, found by a probe of the head. */
            IDLE("idle"),
        }
    }

    /**
     * A message at [offset] of [partition] of [topic] this version cannot decode: [parked] when its key names an
     * aggregate, whose subscriptions park it there; otherwise passed over, the checkpoint moving past it.
     */
    class Undecodable @InternalWowApi constructor(
        val topic: String,
        val partition: Int,
        val offset: Long,
        val parked: Boolean,
    ) : ObservedEvent() {
        override fun fields() =
            listOf("topic" to topic, "partition" to partition, "offset" to offset, "parked" to parked)
    }
}
