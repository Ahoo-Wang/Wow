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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import tools.jackson.databind.JsonNode

/**
 * A decide function, in the form its signature has (decision D36). The kernel calls a [Sync] one directly, with no
 * continuation and no dispatch; only a [Suspending] one runs as a coroutine call.
 */
sealed interface DecideFunction<S : Any> {
    /** Whether the command may, or must, create the aggregate. */
    val creation: Creation

    class Sync<S : Any>(override val creation: Creation, val decide: DecideScope.(S, Any) -> Decision) :
        DecideFunction<S>

    class Suspending<S : Any>(
        override val creation: Creation,
        val decide: suspend DecideScope.(S, Any) -> Decision,
    ) : DecideFunction<S>
}

/** How a command relates to the aggregate's existence (9.x `@CreateAggregate` and `@AllowCreate`). */
enum class Creation {
    /** The aggregate must exist. */
    NONE,

    /** The command creates the aggregate: it must not exist yet. */
    REQUIRED,

    /** The command creates the aggregate when it does not exist, and acts on it when it does. */
    ALLOWED,
}

/**
 * An after hook (decision D39): runs after the decide function of every command it [applies] to, sees the same,
 * not yet evolved state, and returns events appended after the command's own, in the same event stream. It does not
 * run when the command is rejected. Like a decide function it keeps its form (D36).
 */
sealed interface AfterFunction<S : Any> {
    val applies: (Class<*>) -> Boolean

    class Sync<S : Any>(override val applies: (Class<*>) -> Boolean, val after: DecideScope.(S, Any) -> List<Any>) :
        AfterFunction<S>

    class Suspending<S : Any>(
        override val applies: (Class<*>) -> Boolean,
        val after: suspend DecideScope.(S, Any) -> List<Any>,
    ) : AfterFunction<S>
}

/**
 * How an event type is named in the log and which schema revision the model reads it as.
 *
 * Records of an older revision are read through [upgrades]: the function at key `n` turns a revision `n` payload into
 * a revision `n + 1` one, so every revision from 1 up to [revision] needs one (decision D22). [aliases] are names the
 * type had before, so renaming it keeps its history readable.
 */
data class EventType(
    val name: String,
    val revision: Int,
    val type: Class<*>,
    val upgrades: Map<Int, (JsonNode) -> JsonNode> = emptyMap(),
    val aliases: Set<String> = emptySet(),
) {
    init {
        require(revision >= 1) { "An event revision starts at 1." }
        val missing = (1 until revision) - upgrades.keys
        require(missing.isEmpty()) { "$name revision $revision lacks upgrades from revisions $missing." }
        require(upgrades.keys.all { it in 1 until revision }) { "$name has an upgrade beyond its revision." }
    }
}

/**
 * How a command type is named (decision D55): `context.aggregate.CommandName`, the class's simple name unless the
 * model gives another. The log records it with each event stream, and a request id reused by a command of another
 * name is rejected rather than taken for a retry; renaming or moving the class does not change it.
 *
 * [aliases] are names the type had before (`@Alias`): a stream recorded under one of them is this command's, so a
 * request retried after the rename is still a retry, and a command forwarded under a former name is still found.
 */
data class CommandType(val name: String, val type: Class<*>, val aliases: Set<String> = emptySet()) {
    init {
        require(name.isNotBlank()) { "A command type needs a name." }
        require(name !in aliases) { "$name is its own alias." }
    }

    /** Whether [name] is this type's name or one of its former names. */
    fun isNamed(name: String): Boolean = name == this.name || name in aliases
}

/**
 * An aggregate type compiled into tables: what each command type runs and how it is named, how each event type
 * evolves the state, and how each event type is named in the log. Built once, read on every command; the hot path
 * only looks things up.
 *
 * State is mutable by default (decision D7): [evolve] may change [S] in place and return it. The kernel never lets
 * a changed instance outlive a failure: it evolves a state freshly loaded for the attempt and drops it if the
 * attempt does not commit.
 *
 * [stateType] is the class of [S]: snapshots are read back as it, and its schema fingerprint tells whether a snapshot
 * still fits it ([EventCodec.stateSchemaFingerprint]).
 *
 * Every model handles the lifecycle commands of [lifecycle] (design 01 §6; all of them unless it says otherwise)
 * without user code: the kernel decides them, under their system names ([Lifecycle]). A model that declares a decide
 * function for one overrides the kernel's. The lifecycle events are always known, so a log written while a command was
 * enabled still reads; they leave the state as it is unless the model declares an evolve function for them. Only the
 * lifecycle types may take a name in the system namespace.
 */
class AggregateModel<S : Any>(
    val type: AggregateType,
    val stateType: Class<S>,
    private val initial: (AggregateRef) -> S,
    deciders: Map<Class<*>, DecideFunction<S>>,
    evolvers: Map<Class<*>, (S, Any) -> S>,
    eventTypes: Collection<EventType>,
    commandTypes: Collection<CommandType>,
    private val afterFunctions: List<AfterFunction<S>> = emptyList(),
    lifecycle: Set<Class<*>> = Lifecycle.COMMANDS.values.toSet(),
    mountedCommandTypes: Collection<CommandType> = emptyList(),
) {
    private val deciders: Map<Class<*>, DecideFunction<S>> =
        lifecycle.filterNot { it in deciders }.associateWith { KernelLifecycle.decider<S>(it) } + deciders
    private val evolvers: Map<Class<*>, (S, Any) -> S> =
        Lifecycle.EVENTS.values.filterNot { it in evolvers }.associateWith { { state: S, _: Any -> state } } +
            evolvers
    private val eventTypes: List<EventType> = eventTypes + Lifecycle.EVENTS
        .filterValues { event -> eventTypes.none { it.type == event } }
        .map { (name, event) -> EventType(name, 1, event) }

    /**
     * The command types this model decides, each under its name and former names: those of its decide functions,
     * then the lifecycle commands the kernel decides for it ([lifecycleCommands]) under their system names.
     */
    val commandTypes: List<CommandType> = commandTypes + lifecycle
        .filter { command -> commandTypes.none { it.type == command } }
        .map { CommandType(Lifecycle.nameOf(it)!!, it) }
    private val eventsByType: Map<Class<*>, EventType> = this.eventTypes.associateBy { it.type }
    private val eventsByName: Map<String, EventType> = this.eventTypes.flatMap { type ->
        (type.aliases + type.name).map { it to type }
    }.toMap()
    private val commandsByType: Map<Class<*>, CommandType> = this.commandTypes.associateBy { it.type }
    private val commandsByName: Map<String, CommandType> = this.commandTypes.flatMap { type ->
        (type.aliases + type.name).map { it to type }
    }.toMap()
    private val afterByCommand: Map<Class<*>, List<AfterFunction<S>>> =
        this.deciders.keys.associateWith { command -> afterFunctions.filter { it.applies(command) } }

    /**
     * The commands mounted under this aggregate's routes that it does not decide (decision D182): admission finds them
     * by name, and a transformer turns each into a command that is decided.
     */
    val mountedCommandTypes: List<CommandType> = mountedCommandTypes.toList()
    private val mountedByName: Map<String, CommandType> = this.mountedCommandTypes.flatMap { type ->
        (type.aliases + type.name).map { it to type }
    }.toMap()

    /** The lifecycle commands this model handles, by the kernel or by its own decide functions. */
    val lifecycleCommands: Set<Class<*>> = this.deciders.keys.filter { it in KernelLifecycle.COMMANDS }.toSet()

    init {
        require(KernelLifecycle.COMMANDS.containsAll(lifecycle)) {
            "Not lifecycle commands: ${lifecycle - KernelLifecycle.COMMANDS}"
        }
        val names = this.eventTypes.flatMap { it.aliases + it.name }
        require(names.size == names.toSet().size) { "Two event types of $type share a name or an alias." }
        val missing = this.eventTypes.map { it.type } - this.evolvers.keys
        require(missing.isEmpty()) { "Event types of $type without an evolve function: $missing" }
        val commandNames = (this.commandTypes + this.mountedCommandTypes).flatMap { it.aliases + it.name }
        require(commandNames.size == commandNames.toSet().size) {
            "Two command types of $type share a name or an alias."
        }
        val decidedAndMounted = this.mountedCommandTypes.map { it.type }.filter { it in this.deciders }
        require(decidedAndMounted.isEmpty()) { "Command types of $type both decided and mounted: $decidedAndMounted" }
        require(this.mountedCommandTypes.none { isSystemName(it.name) || it.aliases.any(::isSystemName) }) {
            "A mounted command of $type takes a name in ${Lifecycle.NAMESPACE}."
        }
        val unnamed = this.deciders.keys - commandsByType.keys
        require(unnamed.isEmpty()) { "Command types of $type without a name: $unnamed" }
        val named = this.eventTypes.flatMap { event -> (event.aliases + event.name).map { it to event.type } } +
            this.commandTypes.flatMap { command -> (command.aliases + command.name).map { it to command.type } }
        named.forEach { (name, typeClass) ->
            require(isSystemName(name) == (Lifecycle.nameOf(typeClass) != null)) {
                "$type names ${typeClass.name} $name: only the lifecycle types have names in ${Lifecycle.NAMESPACE}."
            }
        }
    }

    private fun isSystemName(name: String): Boolean = name.startsWith("${Lifecycle.NAMESPACE}.")

    /** The state of an aggregate that has no event yet. */
    fun initialState(ref: AggregateRef): S = initial(ref)

    /** The decide function for [commandType], or null when the aggregate does not handle it. */
    fun decider(commandType: Class<*>): DecideFunction<S>? = deciders[commandType]

    /** The after hooks that run for [commandType], in declaration order. */
    fun afterFunctions(commandType: Class<*>): List<AfterFunction<S>> = afterByCommand[commandType].orEmpty()

    /** Applies [event] to [state]. */
    fun evolve(state: S, event: Any): S {
        val evolver = requireNotNull(evolvers[event.javaClass]) { "$type cannot evolve ${event.javaClass.name}." }
        return evolver(state, event)
    }

    /** The log name and revision of [eventClass]. */
    fun eventType(eventClass: Class<*>): EventType =
        requireNotNull(eventsByType[eventClass]) { "${eventClass.name} is not an event of $type." }

    /** The event type named [name] (or formerly named so) in the log. */
    fun eventType(name: String): EventType =
        requireNotNull(findEventType(name)) { "$type has no event named $name." }

    /** The event type named [name] (or formerly named so) in the log, or null when this aggregate has none. */
    fun findEventType(name: String): EventType? = eventsByName[name]

    /**
     * The command type named [name] (or formerly named so), or null when this aggregate has none: how a command that
     * arrives in its wire form (design 08 §3, a forwarded one) finds the class its body is read as.
     */
    fun findCommandType(name: String): CommandType? = commandsByName[name]

    /** The command mounted under this aggregate named [name] (or formerly named so), or null when none is. */
    fun findMountedCommandType(name: String): CommandType? = mountedByName[name]

    /** The name of [commandClass], a command this aggregate decides. */
    fun commandType(commandClass: Class<*>): CommandType =
        requireNotNull(commandsByType[commandClass]) { "${commandClass.name} is not a command of $type." }
}
