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

package me.ahoo.wow.command.kernel

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.messaging.function.FirstParameterKind
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata

/**
 * A sourcing function compiled once per state type and shared by every state aggregate of that type.
 *
 * Only a function that takes the exchange or injects parameters gets one; the others receive the event or its body
 * directly, without an exchange per sourced event.
 */
internal class SourcingFunction<S : Any>(metadata: FunctionAccessorMetadata<S, Void>) {
    private val function = CompiledFunction(metadata)
    private val passesMessage = metadata.firstParameterKind == FirstParameterKind.MESSAGE

    fun invoke(stateRoot: S, domainEvent: DomainEvent<*>) {
        if (function.needsExchange) {
            function.invoke(stateRoot, SimpleDomainEventExchange(domainEvent))
            return
        }
        function.invoke1(stateRoot, if (passesMessage) domainEvent else domainEvent.body)
    }
}

/** Compiles a state type's sourcing functions, keyed by the event body type they source. */
internal fun <S : Any> Map<Class<*>, FunctionAccessorMetadata<S, Void>>.toSourcingTable(): Map<Class<*>, SourcingFunction<S>> =
    entries.associate { it.key to SourcingFunction(it.value) }
