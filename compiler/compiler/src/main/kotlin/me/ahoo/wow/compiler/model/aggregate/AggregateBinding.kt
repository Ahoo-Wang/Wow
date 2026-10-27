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

package me.ahoo.wow.compiler.model.aggregate

import me.ahoo.wow.api.lifecycle.Lifecycle

/** What `ModelSymbolProcessor` found in one aggregate root, before generating its model. */
internal data class AggregateBinding(
    val root: String,
    val packageName: String,
    val rootSimpleName: String,
    val aggregateName: String,
    val state: String,
    /** How a command function is reached: on a root built from the state, or on the state itself. */
    val rootIsState: Boolean,
    /**
     * The state's constructor arguments from an `AggregateRef` (D203): none for the recommended `()`, or `ref.id`
     * (and `ref.tenantId`) for a state that takes the id.
     */
    val stateArguments: List<String>,
    /** What the root's functions take besides the command: parameters of the generated model factory. */
    val dependencies: List<Dependency>,
    val commands: List<CommandBinding>,
    val sourcing: List<SourcingBinding>,
    val afters: List<AfterBinding>,
    /** The `@Upgrade` functions of its events, by event and revision (decision D82). */
    val upgrades: List<UpgradeBinding> = emptyList(),
    val problems: List<String>,
    /** The state's JVM binary name, which keys its schema in the Manifest fragment. */
    val stateType: String,
    /** What the root says it is ([Descriptions]). */
    val description: String?,
    /**
     * The system names of the lifecycle commands the model handles (design 01 §6), sorted: those of
     * `@AggregateRoot(lifecycle)` (decision D91), every one by default, as the kernel enables. `configure` can still
     * call `lifecycle(...)`, which the processor cannot see.
     */
    val lifecycle: List<String> = ALL_LIFECYCLE_COMMANDS,
    /** The stream of its state events the context publishes (decision D156). */
    val publication: Publication = Publication(),
    /** The `@MountedCommand`s mounted under it (decision D182). */
    val mounted: List<MountedBinding> = emptyList(),
    /** `@AggregateRoot(ownership)`: the name of its `Ownership` entry (decision D189), `OWNER` by default. */
    val ownership: String = "OWNER",
    /** `@AggregateRoot(snapshot)`: whether it keeps snapshots (decisions D4, D195), true by default. */
    val snapshot: Boolean = true,
    /** `@AggregateRoot(stateRevision)`: the revision of its state's structure (decision D172), 1 by default. */
    val stateRevision: Int = 1,
    /**
     * `@AggregateRoot(retiredEvents)`: the stored names of the events it no longer has (decision D219), as written,
     * without a dot within the aggregate.
     */
    val retiredEvents: List<String> = emptyList(),
    /** The state member marked `@Tags`, which derives the aggregate's tags (decision D220); null when none is. */
    val tags: TagsBinding? = null,
    /** What compiles but is likely wrong, reported as warnings, such as `@Blocking` on a Reactor function. */
    val warnings: List<String> = emptyList(),
    /** Whether the root or its state is `internal`, so the generated factory is too (finding A4-11). */
    val internal: Boolean = false,
) {
    /** Whether `@AggregateRoot(lifecycle)` narrows the lifecycle commands, which the generated model then says. */
    val narrowsLifecycle: Boolean get() = lifecycle != ALL_LIFECYCLE_COMMANDS

    /** The name of the generated model factory, `<root>Model`. */
    val factoryName: String get() = rootSimpleName.replaceFirstChar { it.lowercase() } + "Model"

    companion object {
        /** Every lifecycle command, by its system name, sorted. */
        val ALL_LIFECYCLE_COMMANDS: List<String> = Lifecycle.COMMANDS.keys.sorted()
    }
}

/**
 * A dependency of the root's functions: a factory parameter [name] of [type], whose class (without type arguments)
 * is [erasure].
 */
/**
 * A parameter of the generated model factory: a domain service of [type], found by its class [erasure] and, when the
 * parameter has a `@Name`, by its [qualifier] (decision D119).
 */
internal class Dependency(val name: String, val type: String, val erasure: String, val qualifier: String?)

internal data class CommandBinding(
    val command: String,
    /** The command's `@Name`, which names it within its aggregate (D55); null for its class's simple name. */
    val commandName: String?,
    /** The command's `@Alias` former names: simple ones within the aggregate, or full type names. */
    val aliases: List<String> = emptyList(),
    val function: String,
    /** Whether the function takes the admitted `Command<X>` rather than the body `X`. */
    val admitted: Boolean,
    /** The arguments after the command: dependency names, or `this` for the decide scope. */
    val arguments: List<String>,
    val suspending: Boolean,
    /** Whether the function returns a Reactor publisher, awaited through wow-reactor. */
    val reactor: Boolean,
    /** `NONE`, `REQUIRED` (`@CreateAggregate`) or `ALLOWED` (`@AllowCreate`). */
    val creation: String,
    /** Whether the command is `@Unaddressed`: its request may name no aggregate, which a transformer finds (D182). */
    val unaddressed: Boolean = false,
    /** The command's JVM binary name, which keys its schema in the Manifest fragment. */
    val type: String,
    /**
     * The classes the function's return type yields as events ([EventYield]), else those `@OnCommand(produces)`
     * declares; null when neither says.
     */
    val yields: List<String>?,
    /** The events `@OnCommand(produces)` declares (decision D120), checked against the aggregate's events. */
    val declaredProduces: List<String>,
    /** `@OnCommand(schedules)`: the full type names of the commands the function declares it schedules (D166). */
    val schedules: List<String> = emptyList(),
    /** What the command's class says it is ([Descriptions]). */
    val description: String? = null,
    /** Whether the function, or one it overrides, is `@Blocking`: the kernel runs it on a virtual thread (D227). */
    val blocking: Boolean = false,
)

/**
 * A `@MountedCommand` (decision D182): a [command] class mounted under the aggregate named [aggregate], which no
 * function decides; a transformer turns it into the commands of [transformsTo], by full type name, when it says.
 */
internal data class MountedBinding(
    val command: String,
    /** The command's `@Name`, which names it within its aggregate (D55); null for its class's simple name. */
    val commandName: String?,
    val aggregate: String,
    /** The command's JVM binary name, which keys its schema in the Manifest fragment. */
    val type: String,
    val transformsTo: List<String>,
    val problems: List<String>,
    /** What the command's class says it is ([Descriptions]). */
    val description: String? = null,
)

internal data class SourcingBinding(
    val event: String,
    val eventName: String?,
    /** The event's `@Revision`, 1 without it. */
    val revision: Int = 1,
    /** The event's `@Alias` former names: simple ones within the aggregate, or full type names. */
    val aliases: List<String> = emptyList(),
    val function: String,
    /** Whether the function returns the new state (immutable form) rather than changing it in place. */
    val returnsState: Boolean,
    /** The event's JVM binary name, which keys its schema in the Manifest fragment. */
    val type: String,
    /** What the event's class says it is ([Descriptions]). */
    val description: String? = null,
    /**
     * The sealed type the function takes, before it is expanded into a binding per concrete subclass (finding A4-9);
     * null for a function of one event class, the only kind the model is generated from.
     */
    val declaration: com.google.devtools.ksp.symbol.KSClassDeclaration? = null,
) {
    /** Whether the function takes a sealed type, which is expanded into its subclasses. */
    val sealed: Boolean get() = declaration != null
}

internal data class AfterBinding(
    val function: String,
    /** The type of the command parameter, the body or `Command<X>`; null when the hook takes no command. */
    val parameter: String?,
    /** Whether the hook takes the admitted `Command<X>` rather than the body. */
    val admitted: Boolean = false,
    val arguments: List<String>,
    val reactor: Boolean,
    val include: List<String>,
    val exclude: List<String>,
    val suspending: Boolean,
    /** The classes the hook's return type yields as events ([EventYield]); null when it does not say. */
    val yields: List<String>?,
    /** Whether the hook, or one it overrides, is `@Blocking`: the kernel runs it on a virtual thread (D227). */
    val blocking: Boolean = false,
) {
    /** Whether the hook runs after [command], a command type as [CommandBinding.command] renders it. */
    fun appliesTo(command: String): Boolean = (include.isEmpty() || command in include) && command !in exclude
}

/**
 * The published stream of an aggregate's state events, as `@AggregateRoot` declares it (decision D156): none unless
 * [published]; with the state after each commit unless [includeState] is false; on [topic], or on
 * `wow.<context>.<aggregate>.state` when it is null.
 */
internal data class Publication(
    val published: Boolean = true,
    val includeState: Boolean = true,
    val topic: String? = null,
) {
    companion object {
        /** The longest topic name Kafka accepts. */
        const val MAX_TOPIC_LENGTH = 249
        private val TOPIC = Regex("[a-zA-Z0-9._-]+")

        /** Why Kafka would refuse [topic] as a topic name, or null when it accepts it. */
        fun topicProblem(topic: String): String? = when {
            topic.length > MAX_TOPIC_LENGTH ->
                "@AggregateRoot(topic) is ${topic.length} characters long: a Kafka topic has at most $MAX_TOPIC_LENGTH"

            !TOPIC.matches(topic) ->
                "@AggregateRoot(topic) \"$topic\" is not a Kafka topic name: only a-z, A-Z, 0-9, '.', '_' and '-'"

            topic == "." || topic == ".." -> "@AggregateRoot(topic) \"$topic\" is not a Kafka topic name"
            else -> null
        }
    }
}
