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

package me.ahoo.wow.compiler.model

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
    /** The state's constructor arguments from an `AggregateRef`: `ref.id` and maybe `ref.tenantId`. */
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
    /** The root's `@Description`. */
    val description: String?,
    /**
     * The system names of the lifecycle commands the model handles (design 01 §6), sorted: those of
     * `@AggregateRoot(lifecycle)` (decision D91), every one by default, as the kernel enables. `configure` can still
     * call `lifecycle(...)`, which the processor cannot see.
     */
    val lifecycle: List<String> = ALL_LIFECYCLE_COMMANDS,
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
internal data class Dependency(val name: String, val type: String, val erasure: String = type)

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
    /** The command's JVM binary name, which keys its schema in the Manifest fragment. */
    val type: String,
    /** The classes the function's return type yields as events ([EventYield]); null when it does not say. */
    val yields: List<String>?,
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
)

internal data class AfterBinding(
    val function: String,
    val parameter: String?,
    val arguments: List<String>,
    val reactor: Boolean,
    val include: List<String>,
    val exclude: List<String>,
    val suspending: Boolean,
    /** The classes the hook's return type yields as events ([EventYield]); null when it does not say. */
    val yields: List<String>?,
) {
    /** Whether the hook runs after [command], a command type as [CommandBinding.command] renders it. */
    fun appliesTo(command: String): Boolean = (include.isEmpty() || command in include) && command !in exclude
}
