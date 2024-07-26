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
package me.ahoo.wow.modeling.matedata

import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.api.command.DeleteAggregate
import me.ahoo.wow.api.command.RecoverAggregate
import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infra.accessor.constructor.ConstructorAccessor
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.toMessageFunction
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.command.CommandAggregate
import me.ahoo.wow.modeling.command.CommandFunction
import me.ahoo.wow.modeling.command.DefaultDeleteAggregateFunction
import me.ahoo.wow.modeling.command.DefaultRecoverAggregateFunction
import reactor.core.publisher.Mono

data class CommandAggregateMetadata<C : Any>(
    override val aggregateType: Class<C>,
    override val namedAggregate: NamedAggregate,
    val constructorAccessor: ConstructorAccessor<C>,
    val mountedCommands: Set<Class<*>>,
    val commandFunctionRegistry: Map<Class<*>, FunctionAccessorMetadata<C, Mono<*>>>,
    val errorFunctionRegistry: Map<Class<*>, FunctionAccessorMetadata<C, Mono<*>>>
) : NamedTypedAggregate<C>, NamedAggregateDecorator, Metadata, ProcessorInfo {
    override val processorName: String = aggregateType.simpleName
    val registeredDeleteAggregate: Boolean =
        commandFunctionRegistry.keys.any {
            DeleteAggregate::class.java.isAssignableFrom(it)
        }
    val registeredRecoverAggregate: Boolean =
        commandFunctionRegistry.keys.any {
            RecoverAggregate::class.java.isAssignableFrom(it)
        }
    val registeredCommands: List<Class<*>> by lazy {
        commandFunctionRegistry.keys.toList() + mountedCommands
    }

    fun toCommandFunctionRegistry(commandAggregate: CommandAggregate<C, *>): Map<Class<*>, MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>>> {
        return buildMap {
            commandFunctionRegistry
                .map {
                    val actualMessageFunction = it.value
                        .toMessageFunction<C, ServerCommandExchange<*>, Mono<*>>(commandAggregate.commandRoot)
                    it.key to CommandFunction(actualMessageFunction, commandAggregate)
                }.also {
                    putAll(it)
                }

            if (!registeredRecoverAggregate) {
                put(DefaultRecoverAggregate::class.java, DefaultRecoverAggregateFunction(commandAggregate))
            }
            if (!registeredDeleteAggregate) {
                put(DefaultDeleteAggregate::class.java, DefaultDeleteAggregateFunction(commandAggregate))
            }
        }
    }

    fun toErrorFunctionRegistry(commandAggregate: CommandAggregate<C, *>): Map<Class<*>, MessageFunction<C, ServerCommandExchange<*>, Mono<*>>> {
        return errorFunctionRegistry
            .map {
                val actualMessageFunction = it.value
                    .toMessageFunction<C, ServerCommandExchange<*>, Mono<*>>(commandAggregate.commandRoot)
                it.key to actualMessageFunction
            }
            .toMap()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandAggregateMetadata<*>) return false

        return aggregateType == other.aggregateType
    }

    override fun hashCode(): Int {
        return aggregateType.hashCode()
    }

    override fun toString(): String {
        return "CommandAggregateMetadata(aggregateType=$aggregateType)"
    }
}
