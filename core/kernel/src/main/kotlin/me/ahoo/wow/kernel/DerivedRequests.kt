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
import me.ahoo.wow.api.subscribe.SubscriptionContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The requests one execution of a saga's handler derives, keyed by what each asks: its target (type, tenant, and id,
 * or `unassigned` for a creation that leaves it to admission) and its command type, then the request's business key
 * ([CommandRequest.requestId]) when it gives one, else its occurrence among the requests of that execution with the
 * same target and type. The key does not depend on the order the handler builds
 * its commands in: a retry whose reads answer in another order derives the same id for each request it derives again,
 * so the kernel answers each as its own duplicate, never one target's request as another's. A creation without an id
 * takes the same request id again, so admission gives it the aggregate its first attempt created.
 */
internal class DerivedRequests {
    private val occurrences = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * What tells the next request to [ref] of [commandType] in this execution from the others with both: its business
     * [requestId] when it gives one, else its occurrence, from 0.
     */
    fun next(ref: AggregateRef, commandType: Class<*>, requestId: String? = null): String =
        requestId?.let(::keyed) ?: occurrences.computeIfAbsent(keyOf(ref, commandType)) { AtomicInteger() }
            .getAndIncrement().toString()

    companion object {
        private const val UNASSIGNED = "unassigned"

        /** What a request asks: its target and its command type. */
        fun keyOf(ref: AggregateRef, commandType: Class<*>): String =
            "${ref.type}/${ref.tenantId}/${if (ref.isAssigned) ref.id else UNASSIGNED}#${commandType.name}"

        /** The discriminator of a request with the business key [requestId], never an occurrence's. */
        fun keyed(requestId: String): String = "key:$requestId"

        /** A name-based UUID of [name]. */
        fun idOf(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
    }
}

/**
 * The execution of a handler that derives commands, as its context: it turns the requests the handler returned into
 * the commands the subscription sends, as [me.ahoo.wow.api.subscribe.DerivingContext] describes, followed by those
 * the handler recorded through its context (a saga's schedules), in the order it recorded them.
 */
@InternalWowApi
fun interface Derivation {
    fun commandsOf(requests: List<CommandRequest<*>>): List<Command<*>>
}

/** [context] as the [Derivation] of its execution; it must be the kernel's context of a deriving handler. */
internal fun derivationOf(context: SubscriptionContext): Derivation =
    context as? Derivation ?: throw IllegalArgumentException(
        "$context derives no commands: a saga's handler gets the kernel's context of its kind (contextOf).",
    )

/**
 * The command requests a saga's handler returned: nothing (`null`, `Unit`, an empty `Mono`), one [CommandRequest], or
 * several, as an `Iterable` (such as the list a `Flux` was collected into), a `Sequence` or a `Flow`, collected in
 * order. Anything else is a handler that does not return command requests, which fails it.
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
    is Command<*> -> throw IllegalStateException(
        "A saga returns command requests, not the command ${element.id}: return CommandRequest.to(ref, body), " +
            "or CommandRequest.of(body, id).",
    )

    else -> throw IllegalStateException("A saga derives command requests, not $element.")
}
