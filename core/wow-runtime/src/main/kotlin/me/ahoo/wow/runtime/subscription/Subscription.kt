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

package me.ahoo.wow.runtime.subscription

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition

/**
 * A subscription (design 04 §1): a [name], a [filter] and exactly one [handler]. The checkpoint belongs to the
 * name, so one handler never shares a checkpoint with another; a replay or a blue-green rebuild is a new name
 * starting from the beginning (04 §3).
 *
 * [startFrom] applies only when the subscription has no checkpoint yet in a partition. [decoder] turns a committed
 * stream into what [handler] receives.
 */
class Subscription(
    val name: String,
    val filter: SubscriptionFilter,
    val startFrom: StartFrom = StartFrom.BEGINNING,
    val decoder: EntryDecoder = EntryDecoder.RECORDS,
    val handler: suspend (SubscribedEntry) -> Unit,
) {
    init {
        require(name.isNotBlank()) { "A subscription needs a name." }
    }

    override fun toString(): String = "Subscription($name)"
}

/** Where a subscription without a checkpoint starts. */
enum class StartFrom {
    /** From the first entry of the partition: a new projection, or a replay under a new name. */
    BEGINNING,

    /** After the entries already committed when the subscription first starts in the partition. */
    END,
}

/**
 * The streams a subscription handles: those of one bounded [context], of the [aggregates] named (every aggregate of
 * the context when empty), holding at least one event whose stored name is in [events] (any event when empty).
 * Event names are the names the log stores (`context.aggregate.Event`); a renamed event is listed under its former
 * names too. An entry outside the filter still moves the checkpoint.
 */
data class SubscriptionFilter(
    val context: String,
    val aggregates: Set<String> = emptySet(),
    val events: Set<String> = emptySet(),
) {
    init {
        require(context.isNotBlank()) { "A subscription filter needs a context." }
    }

    fun matches(stream: EventStreamRecord): Boolean {
        val type = stream.ref.type
        return type.context == context &&
            (aggregates.isEmpty() || type.name in aggregates) &&
            (events.isEmpty() || stream.events.any { it.name in events })
    }
}

/**
 * What a handler receives: one committed [stream] (its ref, version and events), at its log [position], with its
 * [events] as the subscription's [EntryDecoder] read them.
 *
 * [position] is null when the stream is handled again by [SubscriptionRuntime.resume]: resuming reads the
 * aggregate's streams by version, and the log does not return their positions there. A handler that needs an
 * idempotency key uses (`ref`, `version`), which is stable across both paths.
 */
data class SubscribedEntry(val stream: EventStreamRecord, val position: LogPosition?, val events: List<Any>) {
    val ref: AggregateRef get() = stream.ref
    val version: Int get() = stream.version
}

/**
 * Turns a committed stream into the entry a handler receives. It runs inside the handler's retries, so a stream
 * that cannot be decoded is retried and then parks its aggregate like a failing handler.
 *
 * This is the extension point for the post-commit state of design 04 §1 (`StateEvent`): a decoder that also loads
 * the aggregate's state at the stream's version (snapshot plus tail) hands it to the handler with the events.
 */
fun interface EntryDecoder {
    suspend fun decode(stream: EventStreamRecord, position: LogPosition?): SubscribedEntry

    companion object {
        /** Hands over the stored [me.ahoo.wow.spi.log.EventRecord]s as they are, for sinks and generic handlers. */
        val RECORDS: EntryDecoder =
            EntryDecoder { stream, position -> SubscribedEntry(stream, position, stream.events) }

        /**
         * Decodes each event into its class through [codec] and the model of the stream's aggregate among
         * [models], upgrading older revisions on the way. A stream of an aggregate with no model fails to decode.
         */
        fun models(models: Collection<AggregateModel<*>>, codec: EventCodec = EventCodec()): EntryDecoder {
            val byType: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
            return EntryDecoder { stream, position ->
                val model = checkNotNull(byType[stream.ref.type]) { "No model of ${stream.ref.type} to decode with." }
                SubscribedEntry(stream, position, stream.events.map { codec.decode(model, it) })
            }
        }
    }
}
