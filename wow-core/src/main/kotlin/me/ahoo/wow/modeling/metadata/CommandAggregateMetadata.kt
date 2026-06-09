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
package me.ahoo.wow.modeling.metadata

import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.abac.ApplyResourceTags
import me.ahoo.wow.api.abac.DefaultApplyResourceTags
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
import me.ahoo.wow.modeling.command.DefaultApplyResourceTagsFunction
import me.ahoo.wow.modeling.command.DefaultDeleteAggregateFunction
import me.ahoo.wow.modeling.command.DefaultRecoverAggregateFunction
import me.ahoo.wow.modeling.command.after.AfterCommandFunction
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata.Companion.toAfterCommandFunction
import reactor.core.publisher.Mono

/**
 * Represents the metadata for a command aggregate, containing all information needed to process commands.
 *
 * This data class holds the configuration and function registries for a command aggregate, including
 * constructor accessors, command handlers, error handlers, and after-command functions. It provides
 * utilities to convert these into executable message functions.
 *
 * @param C The type of the command aggregate.
 * @property aggregateType The class of the command aggregate.
 * @property namedAggregate The named aggregate that this command aggregate belongs to.
 * @property constructorAccessor The accessor for creating command aggregate instances.
 * @property mountedCommands Set of command types that are mounted on this aggregate.
 * @property commandFunctionRegistry Map of command types to their function metadata for command handling.
 * @property errorFunctionRegistry Map of error types to their function metadata for error handling.
 * @property afterCommandFunctionRegistry List of after-command function metadata.
 *
 * @constructor Creates a new CommandAggregateMetadata with the specified properties.
 */
data class CommandAggregateMetadata<C : Any>(
    override val aggregateType: Class<C>,
    override val namedAggregate: NamedAggregate,
    val constructorAccessor: ConstructorAccessor<C>,
    val mountedCommands: Set<Class<*>>,
    val commandFunctionRegistry: Map<Class<*>, FunctionAccessorMetadata<C, Mono<*>>>,
    val errorFunctionRegistry: Map<Class<*>, FunctionAccessorMetadata<C, Mono<*>>>,
    val afterCommandFunctionRegistry: List<AfterCommandFunctionMetadata<C>> = emptyList()
) : NamedTypedAggregate<C>,
    NamedAggregateDecorator,
    Metadata,
    ProcessorInfo {
    /**
     * The name of this processor, used for identification in processing contexts.
     */
    override val processorName: String = aggregateType.simpleName

    /**
     * Indicates whether delete aggregate functionality is registered for this command aggregate.
     *
     * @return true if any command in the registry is assignable from [DeleteAggregate].
     */
    val registeredDeleteAggregate: Boolean =
        commandFunctionRegistry.keys.any {
            DeleteAggregate::class.java.isAssignableFrom(it)
        }

    /**
     * Indicates whether recover aggregate functionality is registered for this command aggregate.
     *
     * @return true if any command in the registry is assignable from [RecoverAggregate].
     */
    val registeredRecoverAggregate: Boolean =
        commandFunctionRegistry.keys.any {
            RecoverAggregate::class.java.isAssignableFrom(it)
        }

    val registeredApplyResourceTags: Boolean =
        commandFunctionRegistry.keys.any {
            ApplyResourceTags::class.java.isAssignableFrom(it)
        }

    /**
     * The list of all registered commands for this aggregate, including both function-registered and mounted commands.
     *
     * Commands are sorted by their order annotation for consistent processing.
     */
    val registeredCommands: List<Class<*>> by lazy {
        (commandFunctionRegistry.keys.toList() + mountedCommands).sortedByOrder()
    }

    internal fun toCommandFunction(
        commandAggregate: CommandAggregate<C, *>,
        commandType: Class<*>
    ): MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>>? {
        commandFunctionRegistry[commandType]?.let { functionMetadata ->
            val actualMessageFunction = functionMetadata
                .toMessageFunction<C, ServerCommandExchange<*>, Mono<*>>(commandAggregate.commandRoot)
            return CommandFunction(
                delegate = actualMessageFunction,
                commandAggregate = commandAggregate,
                afterCommandFunctions = toAfterCommandFunctions(commandAggregate.commandRoot, commandType),
            )
        }
        return toDefaultCommandFunction(commandAggregate, commandType)
    }

    private fun toDefaultCommandFunction(
        commandAggregate: CommandAggregate<C, *>,
        commandType: Class<*>
    ): MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>>? {
        return when (commandType) {
            DefaultRecoverAggregate::class.java ->
                if (registeredRecoverAggregate) {
                    null
                } else {
                    DefaultRecoverAggregateFunction(
                        commandAggregate,
                        toAfterCommandFunctions(commandAggregate.commandRoot, commandType),
                    )
                }

            DefaultDeleteAggregate::class.java ->
                if (registeredDeleteAggregate) {
                    null
                } else {
                    DefaultDeleteAggregateFunction(
                        commandAggregate,
                        toAfterCommandFunctions(commandAggregate.commandRoot, commandType),
                    )
                }

            DefaultApplyResourceTags::class.java ->
                if (registeredApplyResourceTags) {
                    null
                } else {
                    DefaultApplyResourceTagsFunction(
                        commandAggregate,
                        toAfterCommandFunctions(commandAggregate.commandRoot, commandType),
                    )
                }

            else -> null
        }
    }

    private fun toAfterCommandFunctions(
        commandRoot: C,
        commandType: Class<*>
    ): List<AfterCommandFunction<C>> {
        return afterCommandFunctionRegistry
            .asSequence()
            .filter { it.supportCommand(commandType) }
            .map { it.toAfterCommandFunction(commandRoot) }
            .toList()
    }

    internal fun toErrorFunction(
        commandRoot: C,
        commandType: Class<*>
    ): MessageFunction<C, ServerCommandExchange<*>, Mono<*>>? =
        errorFunctionRegistry[commandType]
            ?.toMessageFunction<C, ServerCommandExchange<*>, Mono<*>>(commandRoot)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandAggregateMetadata<*>) return false

        return aggregateType == other.aggregateType
    }

    override fun hashCode(): Int = aggregateType.hashCode()

    override fun toString(): String = "CommandAggregateMetadata(aggregateType=$aggregateType)"
}
