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

import me.ahoo.wow.serialization.asJsonString

data class WowMetadata(
    /**
     * `contextName` -> `BoundedContext`
     */
    val contexts: Map<String, BoundedContext> = emptyMap()
) : Merge<WowMetadata> {
    init {
        detectAliasConflicts()
    }

    override fun merge(other: WowMetadata): WowMetadata {
        val mergedContexts = contexts.merge(other.contexts)
        return WowMetadata(mergedContexts)
    }

    private fun detectAliasConflicts() {
        contexts.keys.groupBy {
            contexts[it]!!.alias
        }.filter {
            it.key.isNullOrBlank().not()
        }.forEach { (alias, contextNames) ->
            check(contextNames.size == 1) {
                "The alias[$alias] conflicts with the bounded contexts${contextNames.asJsonString()}."
            }
        }
    }
}

data class BoundedContext(
    val alias: String? = null,
    override val scopes: Set<String> = setOf(),
    /**
     * `aggregateName` -> `Aggregate`
     */
    val aggregates: Map<String, Aggregate> = emptyMap()
) : NamingScopes, Merge<BoundedContext> {
    override fun merge(other: BoundedContext): BoundedContext {
        if (alias != null && other.alias != null) {
            check(alias == other.alias) {
                "The current bounded context alias[$alias] conflicts with the bounded context[${other.alias}] to be merged."
            }
        }
        val mergedAlias = firstNotBlank(alias, other.alias)
        val mergedScopes = scopes.plus(other.scopes)
        val mergedAggregates = aggregates.merge(other.aggregates)
        return BoundedContext(alias = mergedAlias, scopes = mergedScopes, aggregates = mergedAggregates)
    }
}

data class Aggregate(
    override val scopes: Set<String> = emptySet(),
    /**
     * Aggregate type fully qualified name
     */
    val type: String? = null,
    /**
     * Static tenant ID
     */
    val tenantId: String? = null,
    /**
     * Custom ID generator name
     */
    val id: String? = null,
    val commands: Set<String> = emptySet(),
    val events: Set<String> = emptySet()
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

        val mergedType: String? = firstNotBlank(type, other.type)
        val mergedTenantId: String? = firstNotBlank(tenantId, other.tenantId)
        val mergedId: String? = firstNotBlank(id, other.id)

        return Aggregate(
            scopes = mergedScopes,
            type = mergedType,
            tenantId = mergedTenantId,
            id = mergedId,
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

private fun firstNotBlank(first: String?, second: String?): String? {
    if (first.isNullOrBlank()) {
        return second
    }
    return first
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
