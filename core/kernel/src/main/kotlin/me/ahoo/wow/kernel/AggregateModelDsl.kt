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
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import tools.jackson.databind.JsonNode

/** Builds an [AggregateModel] of state [S] for the aggregate [name] of bounded context [context]. */
inline fun <reified S : Any> aggregate(
    context: String,
    name: String,
    noinline block: AggregateModelBuilder<S>.() -> Unit,
): AggregateModel<S> = aggregate(S::class.java, context, name, block)

/** Builds an [AggregateModel] of state [stateType] for the aggregate [name] of bounded context [context]. */
fun <S : Any> aggregate(
    stateType: Class<S>,
    context: String,
    name: String,
    block: AggregateModelBuilder<S>.() -> Unit,
): AggregateModel<S> = AggregateModelBuilder(AggregateType(context, name), stateType).apply(block).build()

/**
 * The Kotlin DSL of an aggregate model. Command and event types are named `context.aggregate.Name` after their class's
 * simple name unless a name is given (decision D55).
 */
class AggregateModelBuilder<S : Any>(val type: AggregateType, val stateType: Class<S>) {
    private var initial: ((AggregateRef) -> S)? = null
    private val deciders = LinkedHashMap<Class<*>, DecideFunction<S>>()
    private val commandNames = LinkedHashMap<Class<*>, String>()
    private val afterFunctions = ArrayList<AfterFunction<S>>()
    private val evolvers = LinkedHashMap<Class<*>, (S, Any) -> S>()
    private val eventNames = LinkedHashMap<Class<*>, Pair<String, Int>>()
    private val upgrades = HashMap<Class<*>, MutableMap<Int, (JsonNode) -> JsonNode>>()
    private val aliases = HashMap<Class<*>, MutableSet<String>>()
    private var lifecycle: Set<Class<*>> = Lifecycle.COMMANDS.values.toSet()
    private val mounted = LinkedHashMap<Class<*>, String>()
    private var ownership: Ownership = Ownership.OWNER
    private var stateRevision: Int = 1
    private val retired = ArrayList<String>()

    /** The state of an aggregate that has no event yet. */
    fun initial(factory: (AggregateRef) -> S) {
        initial = factory
    }

    /** Decides commands of type [C], named [name], with a plain function: the fast path (D36). */
    inline fun <reified C : Any> decide(
        creation: Creation = Creation.NONE,
        name: String = defaultName(C::class.java),
        noinline decide: DecideScope.(S, C) -> Decision,
    ) {
        @Suppress("UNCHECKED_CAST")
        addDecider(C::class.java, name, DecideFunction.Sync(creation, decide as DecideScope.(S, Any) -> Decision))
    }

    /** Decides commands of type [C], named [name], with a suspending function, for decisions that read other systems. */
    inline fun <reified C : Any> decideSuspending(
        creation: Creation = Creation.NONE,
        name: String = defaultName(C::class.java),
        noinline decide: suspend DecideScope.(S, C) -> Decision,
    ) {
        @Suppress("UNCHECKED_CAST")
        addDecider(
            C::class.java,
            name,
            DecideFunction.Suspending(creation, decide as suspend DecideScope.(S, Any) -> Decision)
        )
    }

    /**
     * Mounts commands of type [C], named [name], under this aggregate's routes without deciding them (decision D182):
     * a transformer of the service turns each into a command that is decided.
     */
    inline fun <reified C : Any> mount(name: String = defaultName(C::class.java)) {
        addMounted(C::class.java, name)
    }

    /**
     * An after hook (D39) for every command that is one of [include] (all when empty) and none of [exclude]: its
     * events follow the command's own in the same stream.
     */
    fun after(
        include: Set<Class<*>> = emptySet(),
        exclude: Set<Class<*>> = emptySet(),
        after: DecideScope.(S, Any) -> List<Any>,
    ) {
        afterFunctions += AfterFunction.Sync(applies(include, exclude), after)
    }

    /** A suspending after hook; see [after]. */
    fun afterSuspending(
        include: Set<Class<*>> = emptySet(),
        exclude: Set<Class<*>> = emptySet(),
        after: suspend DecideScope.(S, Any) -> List<Any>,
    ) {
        afterFunctions += AfterFunction.Suspending(applies(include, exclude), after)
    }

    /** Evolves the state by changing it in place: the default, concise form (D7). */
    inline fun <reified E : Any> evolve(
        name: String = defaultName(E::class.java),
        revision: Int = 1,
        noinline evolve: S.(E) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val mutate = evolve as S.(Any) -> Unit
        addEvolver(E::class.java, name, revision) { state, event ->
            state.mutate(event)
            state
        }
    }

    /** Evolves the state by returning a new one, for immutable states. */
    inline fun <reified E : Any> evolveTo(
        name: String = defaultName(E::class.java),
        revision: Int = 1,
        noinline evolve: (S, E) -> S,
    ) {
        @Suppress("UNCHECKED_CAST")
        addEvolver(E::class.java, name, revision, evolve as (S, Any) -> S)
    }

    /** Reads revision [from] payloads of event [E] as revision `from + 1` ones (D22). */
    inline fun <reified E : Any> upgrade(from: Int, noinline upgrade: (JsonNode) -> JsonNode) {
        addUpgrade(E::class.java, from, upgrade)
    }

    /**
     * A former name of the command or event type [T]: records of an event stored under it are read as [T], and a
     * request id recorded with a command under it is a retry of [T] (D55).
     */
    inline fun <reified T : Any> alias(former: String) {
        addAlias(T::class.java, former)
    }

    /**
     * Enables only the lifecycle commands [commands] (design 01 §6); all are enabled unless this is called, and calling
     * it with none disables them. A command disabled is not handled, as any command the model does not decide; the
     * lifecycle events stay readable. A decide function declared for a lifecycle command enables it too.
     */
    fun lifecycle(vararg commands: Class<*>) {
        lifecycle = commands.toSet()
    }

    /**
     * Who the aggregate belongs to besides its tenant (decision D189): [Ownership.OWNER] unless this is called.
     * `@AggregateRoot(ownership)` calls it in a generated model.
     */
    fun ownership(ownership: Ownership) {
        this.ownership = ownership
    }

    /**
     * The revision of the state's structure its snapshots are written at (decision D172): 1 unless this is called.
     * `@AggregateRoot(stateRevision)` calls it in a generated model.
     */
    fun stateRevision(revision: Int) {
        stateRevision = revision
    }

    /**
     * Retires the events stored under [names] (decision D219, 9.x `DroppedEvent`): the aggregate no longer has them,
     * and their records left in the log are skipped, never decoded. Each is a full stored name,
     * `context.aggregate.Event`; none may name a current event or command type, or be one of their former names.
     * `@AggregateRoot(retiredEvents)` calls it in a generated model.
     */
    fun retired(vararg names: String) {
        names.forEach { name ->
            require(name.isNotBlank()) { "$type retires an event with a blank name." }
            require(name !in retired) { "$type retires the event $name twice." }
            retired += name
        }
    }

    /**
     * The name of a command or event type declared without one: `context.aggregate.SimpleName`, or the system name of
     * a lifecycle command or event ([Lifecycle]).
     */
    fun defaultName(typeClass: Class<*>): String = Lifecycle.nameOf(typeClass) ?: "$type.${typeClass.simpleName}"

    fun addDecider(commandType: Class<*>, name: String, decider: DecideFunction<S>) {
        require(deciders.putIfAbsent(commandType, decider) == null) {
            "$type declares two decide functions for ${commandType.name}."
        }
        commandNames[commandType] = name
    }

    fun addMounted(commandType: Class<*>, name: String) {
        require(mounted.putIfAbsent(commandType, name) == null) { "$type mounts ${commandType.name} twice." }
    }

    fun addEvolver(eventClass: Class<*>, name: String, revision: Int, evolver: (S, Any) -> S) {
        require(evolvers.putIfAbsent(eventClass, evolver) == null) {
            "$type declares two evolve functions for ${eventClass.name}."
        }
        eventNames[eventClass] = name to revision
    }

    fun addUpgrade(eventClass: Class<*>, from: Int, upgrade: (JsonNode) -> JsonNode) {
        require(upgrades.getOrPut(eventClass) { HashMap() }.putIfAbsent(from, upgrade) == null) {
            "$type declares two upgrades of ${eventClass.name} from revision $from."
        }
    }

    fun addAlias(typeClass: Class<*>, former: String) {
        require(former.isNotBlank()) { "$type gives ${typeClass.name} a blank alias." }
        aliases.getOrPut(typeClass) { LinkedHashSet() } += former
    }

    fun build(): AggregateModel<S> {
        val unknown = aliases.keys.filterNot { it in eventNames || it in commandNames || it in mounted }
        require(unknown.isEmpty()) {
            "$type gives aliases to ${unknown.map { it.name }}, which are none of its command or event types."
        }
        return model()
    }

    private fun model(): AggregateModel<S> = AggregateModel(
        type = type,
        stateType = stateType,
        initial = requireNotNull(initial) { "$type needs an initial state." },
        deciders = deciders.toMap(),
        evolvers = evolvers.toMap(),
        eventTypes = eventNames.map { (eventClass, nameAndRevision) ->
            EventType(
                name = nameAndRevision.first,
                revision = nameAndRevision.second,
                type = eventClass,
                upgrades = upgrades[eventClass].orEmpty().toMap(),
                aliases = aliases[eventClass].orEmpty().toSet(),
            )
        },
        commandTypes = commandNames.map { (commandClass, name) ->
            CommandType(name, commandClass, aliases[commandClass].orEmpty().toSet())
        },
        afterFunctions = afterFunctions.toList(),
        lifecycle = lifecycle,
        mountedCommandTypes = mounted.map { (commandClass, name) ->
            CommandType(name, commandClass, aliases[commandClass].orEmpty().toSet())
        },
        ownership = ownership,
        stateRevision = stateRevision,
        retiredEvents = retired.toList(),
    )

    private fun applies(include: Set<Class<*>>, exclude: Set<Class<*>>): (Class<*>) -> Boolean =
        { command -> (include.isEmpty() || command in include) && command !in exclude }
}
