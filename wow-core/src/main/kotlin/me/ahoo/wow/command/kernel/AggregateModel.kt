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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.messaging.function.FunctionAccessorMetadata
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.metadata.CommandAggregateMetadata
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * An aggregate type compiled once: what each command type runs, its error function and the shared sourcing table.
 * Command and state aggregates of the type look their functions up here instead of binding them per instance.
 *
 * Command types resolve in this order:
 * 1. a handler declared for exactly the command's type;
 * 2. the built-in recover, delete and apply-resource-tags handlers, unless the aggregate handles that kind itself;
 * 3. a handler declared for a supertype or interface of the command's type, the nearest first (superclasses before
 *    interfaces at each level); `Any` never matches. Resolved on first use, once per command type; when several
 *    supertypes at the nearest distance have handlers, the first wins and a warning names the others (V7).
 *
 * Error functions match the command's exact type. After-command functions apply by their `include`/`exclude` against
 * the command's runtime type.
 */
internal class AggregateModel<C : Any, S : Any>(val metadata: AggregateMetadata<C, S>) {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    private val command: CommandAggregateMetadata<C> = metadata.command

    /** The command root's class name: the processor a command function reports. */
    private val processorName: String = command.aggregateType.simpleName
    private val spaced: Boolean = command.spaced
    private val compiledFunctions = HashMap<FunctionAccessorMetadata<C, Mono<*>>, CompiledFunction<C, Mono<*>>>()
    private val afterFunctions: List<Pair<AfterCommandFunctionMetadata<C>, CompiledFunction<C, Mono<*>>>> =
        command.afterCommandFunctionRegistry.map { it to compile(it.function) }
    private val errorFunctions: Map<Class<*>, CompiledFunction<C, Mono<*>>> =
        command.errorFunctionRegistry.mapValues { compile(it.value) }
    private val commandEntries: Map<Class<*>, CommandEntry<C>> = compileCommandEntries()
    private val polymorphicEntries = ConcurrentHashMap<Class<*>, PolymorphicEntry<C>>()

    val sourcingTable: Map<Class<*>, SourcingFunction<S>> = metadata.state.sourcingTable

    fun commandEntry(commandType: Class<*>): CommandEntry<C>? =
        commandEntries[commandType] ?: polymorphicEntries.computeIfAbsent(commandType) {
            PolymorphicEntry(resolvePolymorphic(it))
        }.entry

    fun errorFunction(commandType: Class<*>): CompiledFunction<C, Mono<*>>? = errorFunctions[commandType]

    private fun compile(function: FunctionAccessorMetadata<C, Mono<*>>): CompiledFunction<C, Mono<*>> =
        compiledFunctions.getOrPut(function) { CompiledFunction(function) }

    private fun afterFunctionsOf(commandType: Class<*>): List<CompiledFunction<C, Mono<*>>> =
        afterFunctions.filter { it.first.supportCommand(commandType) }.map { it.second }

    private fun compileCommandEntries(): Map<Class<*>, CommandEntry<C>> {
        val entries = HashMap<Class<*>, CommandEntry<C>>()
        command.commandFunctionRegistry.forEach { (commandType, function) ->
            entries[commandType] = FunctionCommandEntry(
                handler = compile(function),
                processorName = processorName,
                spaced = spaced,
                afterFunctions = afterFunctionsOf(commandType),
            )
        }
        if (!command.registeredRecoverAggregate) {
            entries[DefaultRecoverAggregate::class.java] =
                builtIn(DefaultRecoverAggregate::class.java, BuiltInCommandEntry.RECOVER)
        }
        if (!command.registeredDeleteAggregate) {
            entries[DefaultDeleteAggregate::class.java] =
                builtIn(DefaultDeleteAggregate::class.java, BuiltInCommandEntry.DELETE)
        }
        if (!command.registeredApplyResourceTags) {
            entries[DefaultApplyResourceTags::class.java] =
                builtIn(DefaultApplyResourceTags::class.java, BuiltInCommandEntry.APPLY_RESOURCE_TAGS)
        }
        return entries
    }

    private fun builtIn(
        commandType: Class<*>,
        handler: (ServerCommandExchange<*>) -> Mono<*>
    ): CommandEntry<C> =
        BuiltInCommandEntry(
            commandType = commandType,
            contextName = command.contextName,
            processorName = processorName,
            spaced = spaced,
            afterFunctions = afterFunctionsOf(commandType),
            handler = handler,
        )

    private fun resolvePolymorphic(commandType: Class<*>): CommandEntry<C>? {
        val candidates = supertypeCandidates(commandType)
        val winner = candidates.firstOrNull() ?: return null
        if (candidates.size > 1) {
            log.warn {
                "Command[${commandType.name}] matches handlers of [${command.aggregateType.name}] for " +
                    "${candidates.map { it.name }} at the same distance: [${winner.name}] handles it. " +
                    "Declare a handler for [${commandType.name}] to choose."
            }
        }
        return commandEntries.getValue(winner).withAfterFunctions(afterFunctionsOf(commandType))
    }

    /**
     * The handled supertypes of [commandType] at the nearest distance that has any, in lookup order: superclasses
     * before interfaces, then declaration order. More than one means the choice is ambiguous.
     */
    internal fun supertypeCandidates(commandType: Class<*>): List<Class<*>> {
        val visited = HashSet<Class<*>>()
        var level = commandType.directSupertypes()
        while (level.isNotEmpty()) {
            val types = level.filter { it != Any::class.java && visited.add(it) }
            val candidates = types.filter { commandEntries.containsKey(it) }
            if (candidates.isNotEmpty()) {
                return candidates
            }
            level = types.flatMap { it.directSupertypes() }
        }
        return emptyList()
    }

    private fun Class<*>.directSupertypes(): List<Class<*>> = listOfNotNull(superclass) + interfaces

    override fun toString(): String = "AggregateModel(aggregateType=${command.aggregateType})"
}

/** A cached polymorphic lookup; [entry] is `null` when no supertype handler matches. */
private class PolymorphicEntry<C : Any>(val entry: CommandEntry<C>?)
