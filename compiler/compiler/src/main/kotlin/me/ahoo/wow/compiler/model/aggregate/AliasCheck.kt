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

/**
 * Checks `@Alias` values (decisions D22, D55). A value without a dot is a former name within the aggregate, one with
 * dots the full name `context.aggregate.Name`; a value with exactly one dot is neither, so it is an error, and so are
 * a blank value, a value listed twice, a value equal to the type's current name, and a former name that another type
 * of the module has as its name or former name.
 *
 * The context of the module's types is known only when the KSP option `wow.context` or a single `@BoundedContext`
 * names it; without it, a full name is compared only with other full names.
 */
internal object AliasCheck {
    /** Stands for an unknown context in a normalized name, so that it never equals a full name. */
    private const val UNKNOWN_CONTEXT = "\u0000"

    /** The problems of the aliases of the [kind] (`command` or `event`) [simpleName], named [name] in [aggregate]. */
    fun problems(
        kind: String,
        simpleName: String,
        name: String,
        aliases: List<String>,
        aggregate: String,
        context: String?,
    ): List<String> {
        val where = "$kind $simpleName's @Alias"
        val current = context?.let { "$it.$aggregate.$name" }
        val each = aliases.distinct().mapNotNull { alias ->
            when {
                alias.isBlank() -> "$where has a blank value"
                alias.count { it == '.' } == 1 ->
                    "$where(\"$alias\") has exactly one dot: a former name within the aggregate has none, and a " +
                        "full type name is context.aggregate.Name"
                alias == name || alias == current -> "$where(\"$alias\") is the type's current name"
                else -> null
            }
        }
        val twice = aliases.groupBy { it }.filterValues { it.size > 1 }.keys.map { "$where lists \"$it\" twice" }
        return each + twice
    }

    /**
     * The former names that two types of the module claim, across [bindings]: a type's former name that another type
     * of the same kind has as its name or former name. Problems by the [AggregateBinding.root] they belong to.
     */
    fun duplicates(bindings: List<AggregateBinding>, context: String?): Map<String, List<String>> {
        val entries = bindings.flatMap { binding ->
            binding.commands.flatMap { command ->
                entries(binding, "command", command.type, command.commandName, command.aliases, context)
            } + binding.sourcing.flatMap { event ->
                entries(binding, "event", event.type, event.eventName, event.aliases, context)
            }
        }
        return entries.filter { it.alias != null }.mapNotNull { entry ->
            val others = entries.filter {
                it.kind == entry.kind && it.key == entry.key && it.type != entry.type
            }
            if (others.isEmpty()) {
                return@mapNotNull null
            }
            entry.root to "${entry.kind} ${simpleName(entry.type)}'s former name \"${entry.alias}\" is also " +
                others.joinToString { other ->
                    val role = if (other.alias == null) "the name" else "a former name"
                    "$role of ${other.kind} ${simpleName(other.type)} of ${other.root}"
                }
        }.distinct().groupBy({ it.first }, { it.second })
    }

    private data class Entry(
        val root: String,
        val kind: String,
        val type: String,
        /** The name normalized to `context.aggregate.Name`. */
        val key: String,
        /** The alias as written; null for the type's current name. */
        val alias: String?,
    )

    private fun entries(
        binding: AggregateBinding,
        kind: String,
        type: String,
        name: String?,
        aliases: List<String>,
        context: String?,
    ): List<Entry> {
        fun key(
            value: String
        ) = if ('.' in value) value else "${context ?: UNKNOWN_CONTEXT}.${binding.aggregateName}.$value"
        val current = Entry(binding.root, kind, type, key(name ?: simpleName(type)), null)
        return listOf(current) + aliases.filter { it.isNotBlank() }.distinct().map {
            Entry(binding.root, kind, type, key(it), it)
        }
    }

    /** What `Class.getSimpleName` gives for the binary name [type]. */
    fun simpleName(type: String): String = type.substringAfterLast('.').substringAfterLast('$')
}
