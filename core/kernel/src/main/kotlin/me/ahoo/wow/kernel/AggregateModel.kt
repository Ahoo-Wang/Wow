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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.schedule.Schedules
import tools.jackson.databind.JsonNode

/**
 * A decide function, in the form its signature has. The kernel calls a [Sync] one directly, with no
 * continuation and no dispatch; only a [Suspending] one runs as a coroutine call, and a [Suspending.blocking] one
 * (`@Blocking`) on the kernel's blocking dispatcher, a virtual thread.
 */
sealed interface DecideFunction<S : Any> {
    /** Whether the command may, or must, create the aggregate. */
    val creation: Creation

    /**
     * Whether the command is decided on a deleted aggregate too (`@OnCommand(onDeleted = true)`), as the kernel
     * decides `RestoreAggregate`; every other command is refused on one (`wow.aggregate.deleted`).
     */
    val onDeleted: Boolean

    class Sync<S : Any>(
        override val creation: Creation,
        override val onDeleted: Boolean,
        val decide: DecideScope.(S, Any) -> Decision,
    ) : DecideFunction<S> {
        constructor(creation: Creation, decide: DecideScope.(S, Any) -> Decision) : this(creation, false, decide)
    }

    /** A suspending decide function; a [blocking] one blocks its thread, so the kernel runs it on a virtual thread. */
    class Suspending<S : Any>(
        override val creation: Creation,
        val blocking: Boolean,
        override val onDeleted: Boolean,
        val decide: suspend DecideScope.(S, Any) -> Decision,
    ) : DecideFunction<S> {
        constructor(creation: Creation, blocking: Boolean, decide: suspend DecideScope.(S, Any) -> Decision) :
            this(creation, blocking, false, decide)

        constructor(creation: Creation, decide: suspend DecideScope.(S, Any) -> Decision) :
            this(creation, false, false, decide)
    }
}

/**
 * An after hook: runs after the decide function of every command it [applies] to, sees the same,
 * not yet evolved state, and returns events appended after the command's own, in the same event stream. It does not
 * run when the command is rejected. A hook the model builder makes runs after a system command (a lifecycle or
 * `wow.schedule` one) only when it names it ([AggregateModelBuilder.after]). Like a decide function it
 * keeps its form; a [Suspending.blocking] one runs on a virtual thread.
 */
sealed interface AfterFunction<S : Any> {
    val applies: (Class<*>) -> Boolean

    class Sync<S : Any>(override val applies: (Class<*>) -> Boolean, val after: DecideScope.(S, Any) -> List<Any>) :
        AfterFunction<S>

    /** A suspending after hook; a [blocking] one blocks its thread, so the kernel runs it on a virtual thread. */
    class Suspending<S : Any>(
        override val applies: (Class<*>) -> Boolean,
        val blocking: Boolean,
        val after: suspend DecideScope.(S, Any) -> List<Any>,
    ) : AfterFunction<S> {
        constructor(applies: (Class<*>) -> Boolean, after: suspend DecideScope.(S, Any) -> List<Any>) :
            this(applies, false, after)
    }
}

/**
 * Turns an event's payload of one revision into the next revision's, as an `@Upgrade` function does:
 * the tree of a record as stored, numbers exact, in and out.
 */
fun interface EventUpgrade {
    fun upgrade(payload: JsonNode): JsonNode
}

/**
 * How an event type is named in the log and which schema revision the model reads it as.
 *
 * Records of an older revision are read through its upgrades: the one from revision `n` turns a revision `n` payload
 * into a revision `n + 1` one, so every revision from 1 up to [revision] needs one. [aliases] are names
 * the type had before, so renaming it keeps its history readable. Two event types are equal when they name, revise and
 * read the same way and upgrade from the same revisions; the upgrades themselves are functions, compared by none.
 */
class EventType @InternalWowApi constructor(
    val name: String,
    val revision: Int,
    val type: Class<*>,
    @property:InternalWowApi
    val upgrades: Map<Int, EventUpgrade> = emptyMap(),
    val aliases: Set<String> = emptySet(),
) {
    init {
        require(revision >= 1) { "An event revision starts at 1." }
        val missing = (1 until revision) - upgrades.keys
        require(missing.isEmpty()) { "$name revision $revision lacks upgrades from revisions $missing." }
        require(upgrades.keys.all { it in 1 until revision }) { "$name has an upgrade beyond its revision." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is EventType && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "EventType(name=$name, revision=$revision, type=${type.name}, upgrades=${upgrades.keys}, aliases=$aliases)"

    private fun valueFields(): List<Any?> = listOf(name, revision, type, upgrades.keys, aliases)
}

/**
 * How a command type is named: `context.aggregate.CommandName`, the class's simple name unless the
 * model gives another. The log records it with each event stream, and a request id reused by a command of another
 * name is rejected rather than taken for a retry; renaming or moving the class does not change it.
 *
 * [aliases] are names the type had before (`@Alias`): a stream recorded under one of them is this command's, so a
 * request retried after the rename is still a retry, and a command forwarded under a former name is still found.
 */
class CommandType @InternalWowApi constructor(
    val name: String,
    val type: Class<*>,
    val aliases: Set<String> = emptySet()
) {
    init {
        require(name.isNotBlank()) { "A command type needs a name." }
        require(name !in aliases) { "$name is its own alias." }
    }

    /** Whether [name] is this type's name or one of its former names. */
    fun isNamed(name: String): Boolean = name == this.name || name in aliases

    override fun equals(other: Any?): Boolean =
        this === other || other is CommandType && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CommandType(name=$name, type=$type, aliases=$aliases)"

    private fun valueFields(): List<Any?> = listOf(name, type, aliases)

    companion object {
        /** A CommandType of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(name: String, type: Class<*>, aliases: Set<String> = emptySet()): CommandType = CommandType(
            name,
            type,
            aliases
        )
    }
}

/**
 * An aggregate type compiled into tables: what each command type runs and how it is named, how each event type
 * evolves the state, and how each event type is named in the log. Built once, read on every command; the hot path
 * only looks things up.
 *
 * State is mutable by default: [evolve] may change [S] in place and return it. The kernel never lets
 * a changed instance outlive a failure: it evolves a state freshly loaded for the attempt and drops it if the
 * attempt does not commit.
 *
 * [stateType] is the class of [S]: snapshots are read back as it, and its schema fingerprint tells whether a snapshot
 * still fits it ([EventCodec.stateSchemaFingerprint]).
 *
 * Every model handles the lifecycle commands of [lifecycle] (all of them unless it says otherwise)
 * without user code: the kernel decides them, under their system names ([Lifecycle]). A model that declares a decide
 * function for one overrides the kernel's, to add domain rules to it: it decides that command's own lifecycle event
 * beside the aggregate's own events, `NoOp` or a rejection, and another lifecycle event fails the command
 * ([LifecycleOverride]). The compiler never generates one, since an annotated model's decide function takes no
 * lifecycle command: its own command produces the lifecycle event beside its events instead (decision D336), which the
 * kernel evolves as it evolves the lifecycle commands' events. The lifecycle events are always known, so a log written while a command was
 * enabled still reads; they leave the state as it is unless the model declares an evolve function for them. Only the
 * lifecycle types may take a name in the system namespace.
 *
 * [ownership] is who the aggregate belongs to besides its tenant: an [Ownership.TENANT] aggregate
 * records no owner and checks no command's owner (the kernel keeps only [Ownership.scopeOf] of a command's scope), so
 * it has no owner to transfer: it never handles [TransferOwner].
 *
 * [stateRevision] is the revision of the state's structure its snapshots are written at: a snapshot
 * of another revision is not read back, and the service rebuilds the stored ones in place when it is raised.
 *
 * [retiredEvents] are the stored names of events the aggregate no longer has (9.x `DroppedEvent`): the
 * class is gone, but the log still holds records under the name. Such a record is skipped, never decoded: replaying
 * the stream applies nothing for it, though the stream still counts as a version, and a subscription passes over it.
 * A name that is neither an event's nor retired still fails to read. A retired name is not blank, is listed once, and
 * is none of the model's event or command names, current or former.
 *
 * [tags] derives tags from the state (`@Tags`; 9.x `StateAggregateTagsExtractor`): the keys it returns
 * override the applied tags ([tagsOf]), and the kernel derives them again after every commit and every load.
 */
class AggregateModel<S : Any> @InternalWowApi constructor(
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
    val ownership: Ownership = Ownership.OWNER,
    val stateRevision: Int = 1,
    retiredEvents: Collection<String> = emptyList(),
    private val tags: ((S) -> Map<String, Collection<String>>)? = null,
) {
    /** The stored names of the events this aggregate retired: their records are skipped. */
    val retiredEvents: Set<String> = retiredEvents.toSet()

    private val lifecycle: Set<Class<*>> =
        if (ownership == Ownership.TENANT) lifecycle - TransferOwner::class.java else lifecycle

    /** The system commands the kernel decides for this model: its lifecycle ones and the schedule ones. */
    private val systemCommands: Set<Class<*>> = this.lifecycle + KernelLifecycle.SCHEDULE_COMMANDS

    private val deciders: Map<Class<*>, DecideFunction<S>> =
        systemCommands.filterNot { it in deciders }
            .associateWith { KernelLifecycle.decider(it, ::tagsOf, ::decides) } + deciders
    private val evolvers: Map<Class<*>, (S, Any) -> S> =
        SYSTEM_EVENTS.values.filterNot { it in evolvers }.associateWith { { state: S, _: Any -> state } } +
            evolvers
    private val eventTypes: List<EventType> = eventTypes + SYSTEM_EVENTS
        .filterValues { event -> eventTypes.none { it.type == event } }
        .map { (name, event) -> EventType(name, 1, event) }

    /**
     * The command types this model decides, each under its name and former names: those of its decide functions,
     * then the lifecycle commands the kernel decides for it ([lifecycleCommands]) under their system names.
     */
    val commandTypes: List<CommandType> = commandTypes + systemCommands
        .filter { command -> commandTypes.none { it.type == command } }
        .map { CommandType(KernelLifecycle.systemNameOf(it)!!, it) }
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
     * The commands mounted under this aggregate's routes that it does not decide: admission finds them
     * by name, and a transformer turns each into a command that is decided.
     */
    val mountedCommandTypes: List<CommandType> = mountedCommandTypes.toList()
    private val mountedByName: Map<String, CommandType> = this.mountedCommandTypes.flatMap { type ->
        (type.aliases + type.name).map { it to type }
    }.toMap()

    /** The schedule system events, which every aggregate may commit, and the lifecycle events. */
    private companion object {
        val SYSTEM_EVENTS: Map<String, Class<*>> = Lifecycle.EVENTS + Schedules.EVENTS
    }

    /** The lifecycle commands this model handles, by the kernel or by its own decide functions. */
    val lifecycleCommands: Set<Class<*>> = this.deciders.keys.filter { it in KernelLifecycle.COMMANDS }.toSet()

    init {
        require(stateRevision >= 1) { "The state revision of $type starts at 1, not $stateRevision." }
        require(ownership == Ownership.OWNER || TransferOwner::class.java !in deciders) {
            "$type belongs to its tenant (Ownership.TENANT): it has no owner to transfer, so it decides no TransferOwner."
        }
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
        retiredEvents.groupBy { it }.filterValues { it.size > 1 }.keys.let { twice ->
            require(twice.isEmpty()) { "$type retires the events $twice twice." }
        }
        require(retiredEvents.none { it.isBlank() }) { "$type retires an event with a blank name." }
        val known = this.retiredEvents.filter { it in names || it in commandNames }
        require(known.isEmpty()) {
            "$type retires $known, which name its current event or command types (or name them formerly)."
        }
        val decidedAndMounted = this.mountedCommandTypes.map { it.type }.filter { it in this.deciders }
        require(decidedAndMounted.isEmpty()) { "Command types of $type both decided and mounted: $decidedAndMounted" }
        require(
            this.mountedCommandTypes.none { isSystemName(it.name) || it.aliases.any { name -> isSystemName(name) } }
        ) {
            "A mounted command of $type takes a name in ${Lifecycle.NAMESPACE}."
        }
        val unnamed = this.deciders.keys - commandsByType.keys
        require(unnamed.isEmpty()) { "Command types of $type without a name: $unnamed" }
        val named = this.eventTypes.flatMap { event -> (event.aliases + event.name).map { it to event.type } } +
            this.commandTypes.flatMap { command -> (command.aliases + command.name).map { it to command.type } }
        named.forEach { (name, typeClass) ->
            require(isSystemName(name) == (KernelLifecycle.systemNameOf(typeClass) != null)) {
                "$type names ${typeClass.name} $name: only the lifecycle types have names in ${Lifecycle.NAMESPACE}."
            }
        }
    }

    private fun isSystemName(name: String): Boolean = KernelLifecycle.isSystemName(name)

    /** The state of an aggregate that has no event yet. */
    fun initialState(ref: AggregateRef): S = initial(ref)

    /** The decide function for [commandType], or null when the aggregate does not handle it. */
    fun decider(commandType: Class<*>): DecideFunction<S>? = deciders[commandType]

    /** The after hooks that run for [commandType], in declaration order. */
    fun afterFunctions(commandType: Class<*>): List<AfterFunction<S>> = afterByCommand[commandType].orEmpty()

    /**
     * The aggregate's tags in [state] with the tags [applied]: [applied], each key the state derives
     * replaced by its derived values, or removed when it derives none. Without a tags function, [applied] as is. The
     * derived values are copied, so a mutable state evolving further does not change them.
     */
    internal fun tagsOf(state: S, applied: Map<String, List<String>>): Map<String, List<String>> {
        val derive = tags ?: return applied
        val derived = derive(state)
        if (derived.isEmpty()) {
            return applied
        }
        val effective = LinkedHashMap(applied)
        derived.forEach { (key, values) ->
            if (values.isEmpty()) effective.remove(key) else effective[key] = values.toList()
        }
        return effective
    }

    /** Applies [event] to [state]. */
    fun evolve(state: S, event: Any): S {
        val evolver = requireNotNull(evolvers[event.javaClass]) { "$type cannot evolve ${event.javaClass.name}." }
        return evolver(state, event)
    }

    /** The log name and revision of [eventClass]. */
    fun eventType(eventClass: Class<*>): EventType =
        requireNotNull(eventsByType[eventClass]) { "${eventClass.name} is not an event of $type." }

    /**
     * The event type named [name] (or formerly named so) in the log. A retired name ([isRetired]) has none: its
     * records are skipped, not read.
     */
    fun eventType(name: String): EventType = requireNotNull(findEventType(name)) {
        if (isRetired(name)) {
            "$type retired the event $name: its records are skipped, never read."
        } else {
            "$type has no event named $name."
        }
    }

    /** Whether [name] is the stored name of an event this aggregate retired ([retiredEvents]). */
    fun isRetired(name: String): Boolean = name in retiredEvents

    /** The event type named [name] (or formerly named so) in the log, or null when this aggregate has none. */
    fun findEventType(name: String): EventType? = eventsByName[name]

    /**
     * The command type named [name] (or formerly named so), or null when this aggregate has none: how a command that
     * arrives in its wire form (a forwarded one) finds the class its body is read as.
     */
    fun findCommandType(name: String): CommandType? = commandsByName[name]

    /**
     * Whether this aggregate decides the command type named [name] (or formerly named so): what a schedule set on it
     * may send; a mounted command is not decided, so a schedule cannot send it.
     */
    private fun decides(name: String): Boolean = findCommandType(name) != null

    /** The command mounted under this aggregate named [name] (or formerly named so), or null when none is. */
    fun findMountedCommandType(name: String): CommandType? = mountedByName[name]

    /** The name of [commandClass], or null when it is no command of this aggregate. */
    fun findCommandType(commandClass: Class<*>): CommandType? = commandsByType[commandClass]

    /** The name of [commandClass], a command this aggregate decides. */
    fun commandType(commandClass: Class<*>): CommandType =
        requireNotNull(commandsByType[commandClass]) { "${commandClass.name} is not a command of $type." }
}
