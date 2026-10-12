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
    val problems: List<String>,
)

/** A dependency of the root's functions: a factory parameter [name] of [type]. */
internal data class Dependency(val name: String, val type: String)

internal data class CommandBinding(
    val command: String,
    /** The command's `@Name`, which names it within its aggregate (D55); null for its class's simple name. */
    val commandName: String?,
    /** The command's `@Alias` former names; recorded, not bound: the kernel's command types have none yet. */
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
)

internal data class AfterBinding(
    val function: String,
    val parameter: String?,
    val arguments: List<String>,
    val reactor: Boolean,
    val include: List<String>,
    val exclude: List<String>,
    val suspending: Boolean,
)
