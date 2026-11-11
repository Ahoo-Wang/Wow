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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.spi.id.DerivedIds
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The requests one execution of a saga's handler derives, keyed by what each asks: its target (type, tenant, and id,
 * [AggregateRef.UNASSIGNED] for a creation that leaves it to admission) and its command type, then the request's
 * business key ([CommandRequest.requestId]) when it gives one, else its occurrence among the requests of that execution
 * with the same target and type. The key does not depend on the order the handler builds its commands in: a retry
 * whose reads answer in another order derives the same id for each request it derives again, so the kernel answers
 * each as its own duplicate, never one target's request as another's. A creation without an id takes the same request
 * id again, so admission gives it the aggregate its first attempt created.
 *
 * Every derived identity is written by one encoder ([DerivedIds.encoded]): each field as its length, `:`,
 * and itself. Two different lists of fields never give one text, whatever characters the fields hold (a `/` or `#` in
 * a tenant, an id or a business key, the id `unassigned`), so two different requests never share an id. An unassigned
 * id needs no marker of its own: no assigned id is [AggregateRef.UNASSIGNED], which the gateway refuses as one.
 */
internal class DerivedRequests {
    private val occurrences = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * What tells the next request to [ref] of [commandType] in this execution from the others with both: its business
     * [requestId] when it gives one ([keyed]), else its occurrence, from 0 ([occurrence]).
     */
    fun next(ref: AggregateRef, commandType: Class<*>, requestId: String? = null): String =
        requestId?.let(::keyed) ?: occurrence(
            occurrences.computeIfAbsent(keyOf(ref, commandType)) { AtomicInteger() }.getAndIncrement(),
        )

    /**
     * What tells the next schedule request ([commandType] `wow.schedule.Set` or `wow.schedule.Cancel`) to [ref] of
     * the schedule [key] from the others: the key, then the request's business [requestId] when it gives one, else its
     * occurrence among the requests of that key, type and target. Requests of two keys never take each other's id,
     * whatever order the handler makes them in.
     */
    fun nextScheduled(ref: AggregateRef, commandType: Class<*>, key: String, requestId: String? = null): String =
        scheduled(
            key,
            requestId?.let(::keyed) ?: occurrence(
                occurrences.computeIfAbsent(DerivedIds.encoded(SCHEDULE, keyOf(ref, commandType), key)) {
                    AtomicInteger()
                }.getAndIncrement(),
            ),
        )

    companion object {
        private const val KEY = "key"
        private const val OCCURRENCE = "occurrence"
        private const val SCHEDULE = "schedule"

        /** What a request asks: its target and its command type. */
        fun keyOf(ref: AggregateRef, commandType: Class<*>): String =
            DerivedIds.encoded(ref.type.context, ref.type.name, ref.tenantId, ref.id, commandType.name)

        /** The discriminator of a request with the business key [requestId], never an occurrence's. */
        fun keyed(requestId: String): String = DerivedIds.encoded(KEY, requestId)

        /** The discriminator of a schedule request of the schedule [key], told from that key's others by [within]. */
        fun scheduled(key: String, within: String): String = DerivedIds.encoded(SCHEDULE, key, within)

        /** The discriminator of the [occurrence]-th request (from 0) of a target and type without a business key. */
        fun occurrence(occurrence: Int): String = DerivedIds.encoded(OCCURRENCE, occurrence.toString())

        /** The fields of [execution]: its subscription's context and name, the commit's aggregate, and its version. */
        fun executionOf(execution: ExecutionId): String = DerivedIds.encoded(
            execution.context,
            execution.subscription,
            execution.ref.type.context,
            execution.ref.type.name,
            execution.ref.tenantId,
            execution.ref.id,
            execution.version.toString(),
        )
    }
}

/**
 * The execution of a handler that derives commands, as its context: it turns the requests the handler returned into
 * the commands the subscription sends, as [me.ahoo.wow.api.subscribe.SagaContext] describes, followed by those
 * the handler recorded through its context (a saga's schedules), in the order it recorded them.
 */
@InternalWowApi
fun interface Derivation {
    fun commandsOf(requests: List<CommandRequest<*>>): List<Command<*>>
}

/** [context] as the [Derivation] of its execution; it must be the kernel's context of a deriving handler. */
internal fun derivationOf(context: SubscriptionContext): Derivation = when (context) {
    is Derivation -> context
    // The saga's context is public; it derives through its execution without naming the internal interface.
    is ExecutionSagaContext -> Derivation(context::commandsOf)
    else -> throw IllegalArgumentException(
        "$context derives no commands: a saga's handler gets the kernel's context of its kind (contextOf).",
    )
}

/**
 * The command requests a saga's handler returned: nothing (`null`, `Unit`, an empty `Mono`), one [CommandRequest], or
 * several, as an `Iterable` (such as the list a `Flux` was collected into), a `Sequence` or a `Flow`, collected in
 * order. Anything else is a handler that does not return command requests: an [InvalidDerivedRequestException].
 */
suspend fun requestsOf(result: Any?): List<CommandRequest<*>> = when (result) {
    null, Unit -> emptyList()
    is Iterable<*> -> result.map(::requestOf)
    is Sequence<*> -> result.map(::requestOf).toList()
    is Flow<*> -> result.toList().map(::requestOf)
    else -> listOf(requestOf(result))
}

private fun requestOf(element: Any?): CommandRequest<*> = when (element) {
    is CommandRequest<*> -> element
    is Command<*> -> throw InvalidDerivedRequestException(
        "A saga returns command requests, not the command ${element.id}: return CommandRequest.to(ref, body), " +
            "or CommandRequest.of(body, id).",
    )

    else -> throw InvalidDerivedRequestException("A saga derives command requests, not $element.")
}

/**
 * A saga's handler derived what is no derived request: an element that is no [CommandRequest] (a [Command], another
 * object), or a request with a wait or a timeout, which the subscription that sends it waits for none of. A defect in
 * the saga, which no retry changes: the handling fails unrecoverable, so the aggregate parks at once.
 */
class InvalidDerivedRequestException(message: String) : IllegalStateException(message)
