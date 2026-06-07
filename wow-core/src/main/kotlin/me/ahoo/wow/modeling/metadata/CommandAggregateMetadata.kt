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
import me.ahoo.wow.modeling.command.SimpleCommandFunction
import me.ahoo.wow.modeling.command.after.AfterCommandFunction
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata.Companion.toAfterCommandFunction
import me.ahoo.wow.modeling.materialize
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

    private val supportedTopics: Set<NamedAggregate> = setOf(namedAggregate.materialize())

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

    fun toCommandFunction(
        commandAggregate: CommandAggregate<C, *>,
        commandType: Class<*>
    ): MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>>? {
        commandFunctionRegistry[commandType]?.let { functionMetadata ->
            return functionMetadata.toCommandFunction(
                commandType = commandType,
                commandAggregate = commandAggregate,
                allAfterCommandFunctions = commandAggregate.toAfterCommandFunctions(),
            )
        }

        return commandAggregate.toDefaultCommandFunction(commandType)
    }

    fun toCommandFunctionRegistry(
        commandAggregate: CommandAggregate<C, *>
    ): Map<Class<*>, MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>>> {
        val allAfterCommandFunctions = commandAggregate.toAfterCommandFunctions()
        val registry =
            LinkedHashMap<Class<*>, MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>>>(
                commandFunctionRegistry.size + DEFAULT_INTERNAL_COMMAND_COUNT,
            )
        commandFunctionRegistry.forEach { (commandType, functionMetadata) ->
            registry[commandType] = functionMetadata.toCommandFunction(
                commandType = commandType,
                commandAggregate = commandAggregate,
                allAfterCommandFunctions = allAfterCommandFunctions,
            )
        }

        commandAggregate.toDefaultCommandFunction(
            commandType = DefaultRecoverAggregate::class.java,
            allAfterCommandFunctions = allAfterCommandFunctions,
        )?.let {
            registry[DefaultRecoverAggregate::class.java] = it
        }
        commandAggregate.toDefaultCommandFunction(
            commandType = DefaultDeleteAggregate::class.java,
            allAfterCommandFunctions = allAfterCommandFunctions,
        )?.let {
            registry[DefaultDeleteAggregate::class.java] = it
        }
        commandAggregate.toDefaultCommandFunction(
            commandType = DefaultApplyResourceTags::class.java,
            allAfterCommandFunctions = allAfterCommandFunctions,
        )?.let {
            registry[DefaultApplyResourceTags::class.java] = it
        }
        return registry
    }

    private fun FunctionAccessorMetadata<C, Mono<*>>.toCommandFunction(
        commandType: Class<*>,
        commandAggregate: CommandAggregate<C, *>,
        allAfterCommandFunctions: List<AfterCommandFunction<C>>
    ): MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>> {
        val afterCommandFunctions = allAfterCommandFunctions.supportCommand(commandType)
        if (injectParameterLength == 0) {
            return SimpleCommandFunction(
                metadata = this,
                commandAggregate = commandAggregate,
                afterCommandFunctions = afterCommandFunctions,
                supportedTopics = supportedTopics,
            )
        }
        val actualMessageFunction = toMessageFunction<C, ServerCommandExchange<*>, Mono<*>>(
            commandAggregate.commandRoot,
        )
        return CommandFunction(
            actualMessageFunction,
            commandAggregate,
            afterCommandFunctions,
            supportedTopics,
        )
    }

    private fun CommandAggregate<C, *>.toDefaultCommandFunction(
        commandType: Class<*>,
        allAfterCommandFunctions: List<AfterCommandFunction<C>> = toAfterCommandFunctions()
    ): MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>>? {
        return when {
            commandType == DefaultRecoverAggregate::class.java && !registeredRecoverAggregate -> {
                val afterCommandFunctions = allAfterCommandFunctions.supportCommand(DefaultRecoverAggregate::class.java)
                DefaultRecoverAggregateFunction(this, afterCommandFunctions, supportedTopics)
            }

            commandType == DefaultDeleteAggregate::class.java && !registeredDeleteAggregate -> {
                val afterCommandFunctions = allAfterCommandFunctions.supportCommand(DefaultDeleteAggregate::class.java)
                DefaultDeleteAggregateFunction(this, afterCommandFunctions, supportedTopics)
            }

            commandType == DefaultApplyResourceTags::class.java && !registeredApplyResourceTags -> {
                val afterCommandFunctions = allAfterCommandFunctions.supportCommand(
                    DefaultApplyResourceTags::class.java,
                )
                DefaultApplyResourceTagsFunction(this, afterCommandFunctions, supportedTopics)
            }

            else -> null
        }
    }

    private fun CommandAggregate<C, *>.toAfterCommandFunctions(): List<AfterCommandFunction<C>> {
        if (afterCommandFunctionRegistry.isEmpty()) {
            return emptyList()
        }
        return afterCommandFunctionRegistry.map {
            it.toAfterCommandFunction(commandRoot)
        }
    }

    private companion object {
        const val DEFAULT_INTERNAL_COMMAND_COUNT = 3
    }

    /**
     * Converts the error function registry into executable message functions.
     *
     * This method creates a map of error types to their corresponding message functions for error handling.
     *
     * @param commandAggregate The command aggregate instance to bind functions to.
     * @return A map of error classes to their message functions.
     */
    fun toErrorFunctionRegistry(
        commandAggregate: CommandAggregate<C, *>
    ): Map<Class<*>, MessageFunction<C, ServerCommandExchange<*>, Mono<*>>> {
        if (errorFunctionRegistry.isEmpty()) {
            return emptyMap()
        }
        return buildMap(errorFunctionRegistry.size) {
            errorFunctionRegistry.forEach { (errorType, functionMetadata) ->
                val actualMessageFunction = functionMetadata
                    .toMessageFunction<C, ServerCommandExchange<*>, Mono<*>>(commandAggregate.commandRoot)
                put(errorType, actualMessageFunction)
            }
        }
    }

    private fun List<AfterCommandFunction<C>>.supportCommand(commandType: Class<*>): List<AfterCommandFunction<C>> {
        if (isEmpty()) {
            return emptyList()
        }
        return filter { function ->
            function.metadata.supportCommand(commandType)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandAggregateMetadata<*>) return false

        return aggregateType == other.aggregateType
    }

    override fun hashCode(): Int = aggregateType.hashCode()

    override fun toString(): String = "CommandAggregateMetadata(aggregateType=$aggregateType)"
}
