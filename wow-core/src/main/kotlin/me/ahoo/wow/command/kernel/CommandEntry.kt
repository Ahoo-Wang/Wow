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

import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.abac.DefaultResourceTagsApplied
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.api.event.DefaultAggregateRecovered
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.flatEvent
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.messaging.propagation.MessagePropagators
import me.ahoo.wow.modeling.command.CommandAggregate
import me.ahoo.wow.reactor.checkpoint
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * What one command type runs: its handler, then the after-command functions that apply to it, turning their results
 * into one domain event stream. Compiled once per (aggregate type, command type).
 *
 * The rules are the ones the bound command functions followed, locked by `CommandKernelCharacterizationTest`:
 * - the exchange reports [function] before the handler runs;
 * - each handler result is recorded as the command invoke result;
 * - without after-command functions an empty handler result completes empty; with them, the handler's events and
 *   theirs are collected into one list, even when that list is empty;
 * - the stream takes the aggregate's version, owner and space when it is built.
 */
internal abstract class CommandEntry<C : Any>(
    val function: FunctionInfo,
    protected val spaced: Boolean,
    private val afterFunctions: List<CompiledFunction<C, Mono<*>>>
) {
    protected abstract fun invokeHandler(aggregate: CommandAggregate<C, *>, exchange: ServerCommandExchange<*>): Mono<*>

    /** The same handler for another command type: a supertype handler matched polymorphically. */
    abstract fun withAfterFunctions(afterFunctions: List<CompiledFunction<C, Mono<*>>>): CommandEntry<C>

    fun invoke(
        aggregate: CommandAggregate<C, *>,
        exchange: ServerCommandExchange<*>,
        messagePropagator: MessagePropagators = MessagePropagators.DEFAULT,
    ): Mono<DomainEventStream> {
        exchange.setFunction(function)
        return invokeWithAfter(aggregate, exchange).map {
            it.toDomainEventStream(
                upstream = exchange.message,
                aggregateVersion = aggregate.version,
                stateOwnerId = aggregate.state.ownerId,
                stateSpaceId = aggregate.state.spaceId,
                commandSpaced = spaced,
                messagePropagator = messagePropagator,
            ).also { eventStream ->
                exchange.setEventStream(eventStream)
            }
        }
    }

    private fun invokeHandlerThenSetResult(
        aggregate: CommandAggregate<C, *>,
        exchange: ServerCommandExchange<*>
    ): Mono<Any> {
        @Suppress("UNCHECKED_CAST")
        return invokeHandler(aggregate, exchange).doOnNext {
            exchange.setCommandInvokeResult(it)
        } as Mono<Any>
    }

    private fun invokeWithAfter(aggregate: CommandAggregate<C, *>, exchange: ServerCommandExchange<*>): Mono<*> {
        if (afterFunctions.isEmpty()) {
            return invokeHandlerThenSetResult(aggregate, exchange)
        }
        val commandRoot = aggregate.commandRoot
        val afterResult = Flux.fromIterable(afterFunctions)
            .concatMap {
                it.invoke(commandRoot, exchange)
            }.flatMapIterable { event ->
                event.flatEvent()
            }
        return invokeHandlerThenSetResult(aggregate, exchange)
            .flatMapIterable {
                it.flatEvent()
            }
            .concatWith(afterResult).collectList()
    }
}

/** A command handler declared on the aggregate. */
internal class FunctionCommandEntry<C : Any>(
    private val handler: CompiledFunction<C, Mono<*>>,
    processorName: String,
    spaced: Boolean,
    afterFunctions: List<CompiledFunction<C, Mono<*>>>
) : CommandEntry<C>(
    function = FunctionInfoData(
        functionKind = handler.metadata.functionKind,
        contextName = handler.metadata.contextName,
        processorName = processorName,
        name = handler.metadata.name,
    ),
    spaced = spaced,
    afterFunctions = afterFunctions,
) {
    private val qualifiedName = "$processorName.${function.name}(${handler.metadata.supportedType.simpleName})"

    override fun invokeHandler(aggregate: CommandAggregate<C, *>, exchange: ServerCommandExchange<*>): Mono<*> =
        handler.invoke(aggregate.commandRoot, exchange)
            .checkpoint {
                "[${aggregate.aggregateId}] Invoke $qualifiedName Command[${exchange.message.id}] [CommandFunction]"
            }

    override fun withAfterFunctions(afterFunctions: List<CompiledFunction<C, Mono<*>>>): CommandEntry<C> =
        FunctionCommandEntry(handler, function.processorName, spaced, afterFunctions)
}

/** The framework's handler for a built-in command the aggregate does not handle itself. */
internal class BuiltInCommandEntry<C : Any>(
    private val commandType: Class<*>,
    contextName: String,
    processorName: String,
    spaced: Boolean,
    afterFunctions: List<CompiledFunction<C, Mono<*>>>,
    private val handler: (ServerCommandExchange<*>) -> Mono<*>
) : CommandEntry<C>(
    function = FunctionInfoData(
        functionKind = FunctionKind.COMMAND,
        contextName = contextName,
        processorName = processorName,
        name = "$processorName.${commandType.simpleName}",
    ),
    spaced = spaced,
    afterFunctions = afterFunctions,
) {
    override fun invokeHandler(aggregate: CommandAggregate<C, *>, exchange: ServerCommandExchange<*>): Mono<*> =
        handler(exchange)

    override fun withAfterFunctions(afterFunctions: List<CompiledFunction<C, Mono<*>>>): CommandEntry<C> =
        BuiltInCommandEntry(commandType, function.contextName, function.processorName, spaced, afterFunctions, handler)

    companion object {
        val RECOVER: (ServerCommandExchange<*>) -> Mono<*> = { DefaultAggregateRecovered.toMono() }
        val DELETE: (ServerCommandExchange<*>) -> Mono<*> = { DefaultAggregateDeleted.toMono() }
        val APPLY_RESOURCE_TAGS: (ServerCommandExchange<*>) -> Mono<*> = {
            DefaultResourceTagsApplied((it.message.body as DefaultApplyResourceTags).tags).toMono()
        }
    }
}
