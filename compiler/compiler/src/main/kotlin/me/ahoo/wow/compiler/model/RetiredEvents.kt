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

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.symbol.KSClassDeclaration
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.Schedules

/**
 * Reads and checks `@AggregateRoot(retiredEvents)` (decision D219): the stored names of the events the aggregate no
 * longer has. A value without a dot is a name within the aggregate, one with dots the full name
 * `context.aggregate.Name`, as an `@Alias` value is ([AliasCheck]); a value with exactly one dot is neither, so it is
 * an error, and so are a blank value, a value listed twice, a system name, and a name or former name of one of the
 * aggregate's events or commands, mounted ones included.
 *
 * The context of the module's types is known only when the KSP option `wow.context` or a single `@BoundedContext`
 * names it; without it, a full name is compared only with other full names, and the kernel checks the rest when it
 * builds the model.
 */
internal object RetiredEvents {
    /** Stands for an unknown context in a normalized name, so that it never equals a full name. */
    private const val UNKNOWN_CONTEXT = "\u0000"
    private const val WHERE = "@AggregateRoot(retiredEvents)"

    private val SYSTEM_NAMES: Set<String> =
        Lifecycle.COMMANDS.keys + Lifecycle.EVENTS.keys + Schedules.COMMANDS.keys + Schedules.EVENTS.keys

    /** The values `@AggregateRoot(retiredEvents)` of [root] lists, as written; none by default. */
    @OptIn(KspExperimental::class)
    fun of(root: KSClassDeclaration): List<String> =
        root.getAnnotationsByType(AggregateRoot::class).firstOrNull()?.retiredEvents?.toList().orEmpty()

    /**
     * The problems of the [retired] names of the aggregate [aggregate], which must name none of its [commands],
     * [sourcing] events and [mounted] commands, in [context] when known.
     */
    @Suppress("LongParameterList")
    fun problems(
        retired: List<String>,
        aggregate: String,
        context: String?,
        commands: List<CommandBinding>,
        sourcing: List<SourcingBinding>,
        mounted: List<MountedBinding>,
    ): List<String> {
        fun key(value: String) = if ('.' in value) value else "${context ?: UNKNOWN_CONTEXT}.$aggregate.$value"
        val named = sourcing.flatMap { event ->
            val simpleName = AliasCheck.simpleName(event.type)
            (listOf(event.eventName ?: simpleName) + event.aliases).map { key(it) to "event $simpleName" }
        } + commands.flatMap { command ->
            val simpleName = AliasCheck.simpleName(command.type)
            (listOf(command.commandName ?: simpleName) + command.aliases).map { key(it) to "command $simpleName" }
        } + mounted.map { command ->
            val simpleName = AliasCheck.simpleName(command.type)
            key(command.commandName ?: simpleName) to "mounted command $simpleName"
        }
        val owners = named.groupBy({ it.first }, { it.second })
        val each = retired.distinct().mapNotNull { name ->
            when {
                name.isBlank() -> "$WHERE has a blank value"
                name in SYSTEM_NAMES -> "$WHERE(\"$name\") is a system name, which every aggregate reads"
                name.count { it == '.' } == 1 ->
                    "$WHERE(\"$name\") has exactly one dot: a name within the aggregate has none, and a full stored " +
                        "name is context.aggregate.Name"

                else -> owners[key(name)]?.let { types ->
                    "$WHERE(\"$name\") is the name or a former name of ${types.distinct().joinToString()}: " +
                        "a retired event is one the aggregate no longer has"
                }
            }
        }
        val twice = retired.groupBy { it }.filterValues { it.size > 1 }.keys.map { "$WHERE lists \"$it\" twice" }
        return each + twice
    }
}
