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

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.naming.TypeNames

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
 * `@Name`, else its simple name, unless a name is given: the name the compiler gives them, so a model
 * written by hand stores what the Manifest says. A name given that disagrees with the class's `@Name`, or an aggregate
 * named otherwise than its `@AggregateRoot` state class, fails here: the log would hold names the Manifest does not.
 */
class AggregateModelBuilder<S : Any>(val type: AggregateType, val stateType: Class<S>) {
    init {
        annotatedAggregateName(stateType)?.let { declared ->
            require(type.name == declared) {
                "The aggregate ${type.context}.${type.name} has the state ${stateType.name}, whose annotations name " +
                    "it $declared: name the aggregate $declared, or change the class's @Name."
            }
        }
    }

    private var initial: ((AggregateRef) -> S)? = null
    private val deciders = LinkedHashMap<Class<*>, DecideFunction<S>>()
    private val commandNames = LinkedHashMap<Class<*>, String>()
    private val afterFunctions = ArrayList<AfterFunction<S>>()
    private val evolvers = LinkedHashMap<Class<*>, (S, Any) -> S>()
    private val eventNames = LinkedHashMap<Class<*>, Pair<String, Int>>()
    private val upgrades = HashMap<Class<*>, MutableMap<Int, EventUpgrade>>()
    private val aliases = HashMap<Class<*>, MutableSet<String>>()
    private var lifecycle: Set<Class<*>> = Lifecycle.COMMANDS.values.toSet()
    private val mounted = LinkedHashMap<Class<*>, String>()
    private var ownership: Ownership = Ownership.OWNER
    private var idSource: AggregateIdSource = AggregateIdSource.ASSIGNED
    private var stateRevision: Int = 1
    private val retired = ArrayList<String>()
    private var tags: ((S) -> Map<String, Collection<String>>)? = null
    private val aggregateIds = LinkedHashMap<Class<*>, (Any) -> String?>()

    /** The state of an aggregate that has no event yet. */
    fun initial(factory: (AggregateRef) -> S) {
        initial = factory
    }

    /**
     * Decides commands of type [C], named [name], with a plain function: the fast path. An [onDeleted] command is
     * decided on a deleted aggregate too ([DecideFunction.onDeleted]). With [produces], the events it may decide, a
     * decision holding another fails the command ([DecideFunction.produces]).
     */
    inline fun <reified C : Any> decide(
        creation: Creation = Creation.NONE,
        name: String = defaultName(C::class.java),
        onDeleted: Boolean = false,
        produces: Set<Class<*>>? = null,
        noinline decide: DecideScope.(S, C) -> Decision,
    ) {
        @Suppress("UNCHECKED_CAST")
        addDecider(
            C::class.java,
            name,
            DecideFunction.Sync(creation, onDeleted, decide as DecideScope.(S, Any) -> Decision, produces),
        )
    }

    /** Decides commands of type [C], named [name], with a suspending function, for decisions that read other systems. */
    inline fun <reified C : Any> decideSuspending(
        creation: Creation = Creation.NONE,
        name: String = defaultName(C::class.java),
        onDeleted: Boolean = false,
        produces: Set<Class<*>>? = null,
        noinline decide: suspend DecideScope.(S, C) -> Decision,
    ) {
        @Suppress("UNCHECKED_CAST")
        addDecider(
            C::class.java,
            name,
            DecideFunction.Suspending(
                creation,
                false,
                onDeleted,
                decide as suspend DecideScope.(S, Any) -> Decision,
                produces,
            ),
        )
    }

    /**
     * Decides commands of type [C], named [name], with a function that blocks its thread (`@Blocking`),
     * such as one calling JDBC: the kernel runs it on its blocking dispatcher, a virtual thread, so it does not
     * hold a worker thread.
     */
    inline fun <reified C : Any> decideBlocking(
        creation: Creation = Creation.NONE,
        name: String = defaultName(C::class.java),
        onDeleted: Boolean = false,
        produces: Set<Class<*>>? = null,
        noinline decide: suspend DecideScope.(S, C) -> Decision,
    ) = decideBlocking(C::class.java, creation, name, onDeleted, produces, decide)

    /** Decides commands of [commandType] with a function that blocks its thread; see the reified overload. */
    fun <C : Any> decideBlocking(
        commandType: Class<C>,
        creation: Creation = Creation.NONE,
        name: String = defaultName(commandType),
        onDeleted: Boolean = false,
        produces: Set<Class<*>>? = null,
        decide: suspend DecideScope.(S, C) -> Decision,
    ) {
        @Suppress("UNCHECKED_CAST")
        addDecider(
            commandType,
            name,
            DecideFunction.Suspending(
                creation,
                true,
                onDeleted,
                decide as suspend DecideScope.(S, Any) -> Decision,
                produces,
            ),
        )
    }

    /**
     * Reads the id of the aggregate a command of type [C] acts on from its body, as its `@AggregateId` property holds
     * it: its request need not name its aggregate. [read] gives null for a creation that leaves it out, which then gets
     * an id. [C] is a command this model decides.
     */
    inline fun <reified C : Any> aggregateId(noinline read: (C) -> String?) {
        @Suppress("UNCHECKED_CAST")
        addAggregateId(C::class.java, read as (Any) -> String?)
    }

    /** Reads the aggregate id of a command of [commandType] from its body; see the reified overload. */
    fun addAggregateId(commandType: Class<*>, read: (Any) -> String?) {
        require(aggregateIds.putIfAbsent(commandType, read) == null) {
            "$type reads the aggregate id of ${commandType.name} twice."
        }
    }

    /**
     * Mounts commands of type [C], named [name], under this aggregate's routes without deciding them:
     * a transformer of the service turns each into a command that is decided.
     */
    inline fun <reified C : Any> mount(name: String = defaultName(C::class.java)) {
        addMounted(C::class.java, name)
    }

    /**
     * An after hook for every command that is one of [include] and none of [exclude]: its events follow the
     * command's own in the same stream. An empty [include] is every command of the model's own decide functions, not
     * the system commands the kernel decides (lifecycle and `wow.schedule`): a hook runs after one of
     * those only when [include] names it. With [produces], the events it may return, any other fails the command
     * ([AfterFunction.produces]).
     */
    fun after(
        include: Set<Class<*>> = emptySet(),
        exclude: Set<Class<*>> = emptySet(),
        produces: Set<Class<*>>? = null,
        after: DecideScope.(S, Any) -> List<Any>,
    ) {
        afterFunctions += AfterFunction.Sync(applies(include, exclude), after, produces)
    }

    /** A suspending after hook; see [after]. */
    fun afterSuspending(
        include: Set<Class<*>> = emptySet(),
        exclude: Set<Class<*>> = emptySet(),
        produces: Set<Class<*>>? = null,
        after: suspend DecideScope.(S, Any) -> List<Any>,
    ) {
        afterFunctions += AfterFunction.Suspending(applies(include, exclude), false, after, produces)
    }

    /** An after hook that blocks its thread (`@Blocking`): it runs on a virtual thread; see [after]. */
    fun afterBlocking(
        include: Set<Class<*>> = emptySet(),
        exclude: Set<Class<*>> = emptySet(),
        produces: Set<Class<*>>? = null,
        after: suspend DecideScope.(S, Any) -> List<Any>,
    ) {
        afterFunctions += AfterFunction.Suspending(applies(include, exclude), true, after, produces)
    }

    /** Evolves the state by changing it in place: the default, concise form. */
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

    /** Reads revision [from] payloads of event [E] as revision `from + 1` ones. */
    inline fun <reified E : Any> upgrade(from: Int, upgrade: EventUpgrade) {
        addUpgrade(E::class.java, from, upgrade)
    }

    /**
     * A former name of the command or event type [T]: records of an event stored under it are read as [T], and a
     * request id recorded with a command under it is a retry of [T].
     */
    inline fun <reified T : Any> alias(former: String) {
        addAlias(T::class.java, former)
    }

    /**
     * Enables only the lifecycle commands [commands]; all are enabled unless this is called, and calling
     * it with none disables them. A command disabled is not handled, as any command the model does not decide; the
     * lifecycle events stay readable. A decide function declared for a lifecycle command enables it too.
     */
    fun lifecycle(vararg commands: Class<*>) {
        lifecycle = commands.toSet()
    }

    /**
     * Who the aggregate belongs to besides its tenant: [Ownership.OWNER] unless this is called.
     * `@AggregateRoot(ownership)` calls it in a generated model.
     */
    fun ownership(ownership: Ownership) {
        this.ownership = ownership
    }

    /**
     * Where the aggregate's id comes from: [AggregateIdSource.ASSIGNED] unless this is called. `@AggregateRoot(idSource)`
     * calls it in a generated model.
     */
    fun id(source: AggregateIdSource) {
        idSource = source
    }

    /**
     * The revision of the state's structure its snapshots are written at: 1 unless this is called.
     * `@AggregateRoot(stateRevision)` calls it in a generated model.
     */
    fun stateRevision(revision: Int) {
        stateRevision = revision
    }

    /**
     * Retires the events stored under [names] (9.x `DroppedEvent`): the aggregate no longer has them,
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
     * Derives the aggregate's tags from its state (9.x `StateAggregateTagsExtractor`): the keys [derive]
     * returns override the applied tags (`ApplyTags`) per key, and a key it returns with no value removes that key. The
     * kernel calls it after every commit and every load, so it is pure and cheap; it should return every key it owns
     * on every call, with an empty collection for none, since a key it stops returning keeps its last derived value.
     * A state member marked `@Tags` calls it in a generated model.
     */
    fun tags(derive: (S) -> Map<String, Collection<String>>) {
        require(tags == null) { "$type declares two tags functions." }
        tags = derive
    }

    /**
     * The name of a command or event type declared without one: `context.aggregate.SimpleName`, or the system name of
     * a lifecycle command or event ([Lifecycle]).
     */
    fun defaultName(typeClass: Class<*>): String = Lifecycle.nameOf(typeClass) ?: "$type.${typeNameOf(typeClass)}"

    /**
     * Adds the decide function of [commandType]. A decide function of a lifecycle command overrides the kernel's, to
     * add domain rules to it: it decides the command's own lifecycle event (`AggregateDeleted` for `DeleteAggregate`)
     * beside the aggregate's own events, `NoOp` or a rejection, and deciding another lifecycle event fails the command
     * ([LifecycleOverride]). An override of `RestoreAggregate` is decided on a deleted aggregate as the kernel's is,
     * so it takes no `onDeleted`. The schedule commands (`wow.schedule.*`) are the kernel's alone: a decide function
     * schedules with `DecideScope.schedule`.
     */
    fun addDecider(commandType: Class<*>, name: String, decider: DecideFunction<S>) {
        requireAnnotatedName("command", commandType, name)
        require(commandType !in KernelLifecycle.SCHEDULE_COMMANDS) {
            "$type declares a decide function for ${commandType.name}, a schedule command the kernel decides: a " +
                "decide function schedules with DecideScope.schedule."
        }
        require(commandType != RestoreAggregate::class.java || !decider.onDeleted) {
            "$type declares its RestoreAggregate onDeleted: it is decided on a deleted aggregate already."
        }
        val guarded = if (commandType in KernelLifecycle.COMMANDS) {
            LifecycleOverride.of(type, commandType, decider)
        } else {
            decider
        }
        require(deciders.putIfAbsent(commandType, guarded) == null) {
            "$type declares two decide functions for ${commandType.name}."
        }
        commandNames[commandType] = name
    }

    fun addMounted(commandType: Class<*>, name: String) {
        requireAnnotatedName("command", commandType, name)
        require(mounted.putIfAbsent(commandType, name) == null) { "$type mounts ${commandType.name} twice." }
    }

    fun addEvolver(eventClass: Class<*>, name: String, revision: Int, evolver: (S, Any) -> S) {
        requireAnnotatedName("event", eventClass, name)
        require(evolvers.putIfAbsent(eventClass, evolver) == null) {
            "$type declares two evolve functions for ${eventClass.name}."
        }
        eventNames[eventClass] = name to revision
    }

    fun addUpgrade(eventClass: Class<*>, from: Int, upgrade: EventUpgrade) {
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
        tags = tags,
        idSource = idSource,
        aggregateIds = aggregateIds.toMap(),
    )

    /** A [name] given for a class that has a `@Name` must be the one that `@Name` gives. */
    private fun requireAnnotatedName(kind: String, typeClass: Class<*>, name: String) {
        val annotated = typeClass.getDeclaredAnnotation(Name::class.java)?.value ?: return
        val expected = Lifecycle.nameOf(typeClass) ?: "$type.$annotated"
        require(name == expected) {
            "$type names the $kind ${typeClass.name} $name, but its @Name names it $expected: leave the name out, " +
                "or give the one its @Name gives."
        }
    }

    private fun applies(include: Set<Class<*>>, exclude: Set<Class<*>>): (Class<*>) -> Boolean = { command ->
        val included = if (include.isEmpty()) command !in SYSTEM_COMMANDS else command in include
        included && command !in exclude
    }

    private companion object {
        /** The commands the kernel decides for every model, which an after hook runs after only when it names them. */
        val SYSTEM_COMMANDS: Set<Class<*>> = KernelLifecycle.COMMANDS + KernelLifecycle.SCHEDULE_COMMANDS
    }
}

/**
 * The last part of the name of a command or event type, as the compiler names it ([TypeNames.typeName]): its class's
 * own `@Name`, else its simple name.
 */
internal fun typeNameOf(typeClass: Class<*>): String =
    TypeNames.typeName(typeClass.simpleName, typeClass.getDeclaredAnnotation(Name::class.java)?.value)

/**
 * The aggregate name the compiler gives [stateType] when it is an `@AggregateRoot` ([TypeNames.aggregateName]), its
 * `@Name` when it carries only that, or null when it carries neither.
 */
private fun annotatedAggregateName(stateType: Class<*>): String? {
    val annotated = stateType.getDeclaredAnnotation(Name::class.java)?.value
    if (annotated != null || !stateType.isAnnotationPresent(AggregateRoot::class.java)) {
        return annotated
    }
    return TypeNames.aggregateName(stateType.simpleName, null)
}
