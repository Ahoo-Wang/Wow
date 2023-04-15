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
package me.ahoo.wow.configuration

data class WowMetadata(
    /**
     * `contextName` -> `BoundedContext`
     */
    val contexts: Map<String, BoundedContext> = emptyMap(),
) : Merge<WowMetadata> {
    override fun merge(other: WowMetadata): WowMetadata {
        val mergedContexts = contexts.merge(other.contexts)
        return WowMetadata(mergedContexts)
    }
}

data class BoundedContext(
    val alias: String = "",
    override val scopes: Set<String> = setOf(),
    /**
     * `aggregateName` -> `Aggregate`
     */
    val aggregates: Map<String, Aggregate> = emptyMap(),
) : NamingScopes, Merge<BoundedContext> {
    override fun merge(other: BoundedContext): BoundedContext {
        if (alias.isNotBlank() && other.alias.isNotBlank()) {
            check(alias == other.alias) {
                "The current bounded context alias[$alias] conflicts with the bounded context[${other.alias}] to be merged."
            }
        }
        val mergedAlias = alias.ifBlank { other.alias }
        val mergedScopes = scopes.plus(other.scopes)
        val mergedAggregates = aggregates.merge(other.aggregates)
        return BoundedContext(mergedAlias, mergedScopes, mergedAggregates)
    }
}

data class Aggregate(
    override val scopes: Set<String> = emptySet(),
    /**
     * Aggregate type fully qualified name
     */
    val type: String? = null,
    val commands: Set<String> = emptySet(),
    val events: Set<String> = emptySet(),
) : NamingScopes, Merge<Aggregate> {
    override fun merge(other: Aggregate): Aggregate {
        val mergedScopes = scopes.plus(other.scopes)
        val mergedCommands = commands.plus(other.commands)
        val mergedEvents = events.plus(other.events)
        if (!type.isNullOrBlank() &&
            !other.type.isNullOrBlank()
        ) {
            check(type == other.type) {
                "The current aggregate type[$type] conflicts with the aggregate[${other.type}] to be merged."
            }
        }

        var mergedType: String? = type
        if (mergedType.isNullOrBlank()) {
            mergedType = other.type
        }
        return Aggregate(
            scopes = mergedScopes,
            type = mergedType,
            commands = mergedCommands,
            events = mergedEvents,
        )
    }
}

interface NamingScopes {
    val scopes: Set<String>
}

interface Merge<T> {
    fun merge(other: T): T
}

internal fun <K, V : Merge<V>> Map<K, V>.merge(other: Map<K, V>): Map<K, V> {
    val source = this
    return mutableMapOf<K, V>().apply {
        putAll(source)
        other.forEach {
            val current = get(it.key)
            if (current == null) {
                put(it.key, it.value)
            } else {
                put(it.key, current.merge(it.value))
            }
        }
    }
}
