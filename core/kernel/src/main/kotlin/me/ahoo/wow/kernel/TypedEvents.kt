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

import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext

/**
 * Calls [handle] once for each event of [stream] that is a [type], in their order, with the
 * [StateTypedEvent] of that event and [context] seen as that call ([invocationOf]): its `invocationKey` names the
 * event, while everything else is the stream's [context]. A saga's commands, those each call returns and those it
 * calls, are told apart by the event the call was for ([DerivedRequests]). What each call returns, in order. The
 * generated bindings call it, as they call [requestsOf].
 */
suspend fun <S, E : Any, C : SubscriptionContext, R> eachEvent(
    stream: StateEvent<S>,
    type: Class<E>,
    context: C,
    handle: suspend (StateTypedEvent<S, E>, C) -> R,
): List<R> = stream.events.withIndex().filter { type.isInstance(it.value) }.map { (index, _) ->
    handle(StateTypedEvent.of(stream, index), invocationOf(context, index)).also { returned ->
        if (context is ExecutionSagaContext) {
            when (returned) {
                is Iterable<*> -> context.returned(returned, index)
                is CommandRequest<*> -> context.returned(listOf(returned), index)
            }
        }
    }
}

/** [context] for the call of a handler of the [eventIndex]-th event of its stream: `invocationKey` is per event. */
@Suppress("UNCHECKED_CAST") // Each branch keeps the kind of context it was given.
internal fun <C : SubscriptionContext> invocationOf(context: C, eventIndex: Int): C {
    val key = "${context.executionId}:$eventIndex"
    return when (context) {
        // Its calls are the call's for its event: told apart from the other calls' by it.
        is ExecutionSagaContext -> object : SagaContext by context {
            override val invocationKey: String = key

            override suspend fun call(request: CommandRequest<*>): CommandResult = context.call(request, eventIndex)
        }

        is SagaContext -> object : SagaContext by context {
            override val invocationKey: String = key
        }

        else -> object : SubscriptionContext by context {
            override val invocationKey: String = key
        }
    } as C
}
