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

import me.ahoo.wow.api.naming.DescriptionCapable
import me.ahoo.wow.serialization.toJsonString

/**
 * Root metadata class containing all bounded context configurations.
 * WowMetadata represents the complete configuration for all bounded contexts and their aggregates.
 *
 * @param contexts Map of context names to their bounded context configurations.
 */
data class WowMetadata(
    /**
     * Map of context names to their bounded context configurations.
     */
    val contexts: Map<String, BoundedContext> = emptyMap()
) : Merge<WowMetadata> {
    init {
        detectAliasConflicts()
    }

    /**
     * Merges this WowMetadata with another, combining contexts and resolving conflicts.
     *
     * @param other The other WowMetadata to merge with.
     * @return A new WowMetadata containing the merged configurations.
     */
    override fun merge(other: WowMetadata): WowMetadata {
        val mergedContexts = contexts.merge(other.contexts)
        return WowMetadata(mergedContexts)
    }

    /**
     * Validates that bounded context aliases are unique across all contexts.
     * Throws an exception if duplicate aliases are found.
     *
     * @throws IllegalArgumentException if alias conflicts are detected.
     */
    private fun detectAliasConflicts() {
        contexts.keys
            .groupBy {
                contexts[it]!!.alias
            }.filter {
                it.key.isNullOrBlank().not()
            }.forEach { (alias, contextNames) ->
                check(contextNames.size == 1) {
                    "The alias[$alias] conflicts with the bounded contexts${contextNames.toJsonString()}."
                }
            }
    }
}

/**
 * Configuration for a bounded context, containing aggregates and their metadata.
 * A bounded context represents a logical boundary within the domain model.
 *
 * @param alias Optional alias for the bounded context (must be unique across all contexts).
 * @param description Human-readable description of the bounded context.
 * @param scopes Set of package scopes that belong to this bounded context.
 * @param aggregates Map of aggregate names to their configurations.
 */
data class BoundedContext(
    val alias: String? = null,
    override val description: String = "",
    override val scopes: Set<String> = linkedSetOf(),
    /**
     * `aggregateName` -> `Aggregate`
     */
    val aggregates: Map<String, Aggregate> = emptyMap()
) : NamingScopes,
    Merge<BoundedContext>,
    DescriptionCapable {
    /**
     * Merges this BoundedContext with another, combining scopes, aggregates, and resolving conflicts.
     *
     * @param other The other BoundedContext to merge with.
     * @return A new BoundedContext containing the merged configurations.
     * @throws IllegalArgumentException if alias conflicts are detected.
     */
    override fun merge(other: BoundedContext): BoundedContext {
        if (alias.isNullOrBlank().not() && other.alias.isNullOrBlank().not()) {
            check(alias == other.alias) {
                "The current bounded context alias[$alias] conflicts with the bounded context[${other.alias}] to be merged."
            }
        }
        val mergedAlias = firstNotBlank(alias, other.alias)
        val mergedDescription = firstNotBlank(description, other.description).orEmpty()
        val mergedScopes = scopes.plus(other.scopes)
        val mergedAggregates = aggregates.merge(other.aggregates)
        return BoundedContext(
            alias = mergedAlias,
            description = mergedDescription,
            scopes = mergedScopes,
            aggregates = mergedAggregates,
        )
    }
}

/**
 * Configuration for an aggregate within a bounded context.
 * Defines the aggregate's type, scopes, commands, events, and other metadata.
 *
 * @param scopes Set of package scopes that belong to this aggregate.
 * @param type Fully qualified name of the aggregate class.
 * @param tenantId Optional static tenant ID for multi-tenant scenarios.
 * @param id Optional custom ID generator name.
 * @param commands Set of command package scopes handled by this aggregate.
 * @param events Set of event package scopes produced by this aggregate.
 */
data class Aggregate(
    override val scopes: Set<String> = linkedSetOf(),
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
    val commands: Set<String> = linkedSetOf(),
    val events: Set<String> = linkedSetOf()
) : NamingScopes,
    Merge<Aggregate> {
    /**
     * Merges this Aggregate with another, combining scopes, commands, events, and resolving conflicts.
     *
     * @param other The other Aggregate to merge with.
     * @return A new Aggregate containing the merged configurations.
     * @throws IllegalArgumentException if type conflicts are detected.
     */
    override fun merge(other: Aggregate): Aggregate {
        val mergedScopes =
            linkedSetOf<String>().apply {
                addAll(scopes)
                addAll(other.scopes)
            }
        val mergedCommands =
            linkedSetOf<String>().apply {
                addAll(commands)
                addAll(other.commands)
            }
        val mergedEvents =
            linkedSetOf<String>().apply {
                addAll(events)
                addAll(other.events)
            }
        if (type.isNullOrBlank().not() && other.type.isNullOrBlank().not()) {
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

/**
 * Interface for entities that have package scopes.
 * Scopes define the packages that belong to this entity.
 */
interface NamingScopes {
    val scopes: Set<String>
}

/**
 * Interface for objects that can be merged with others of the same type.
 * Used for combining metadata from multiple sources.
 *
 * @param T The type of objects that can be merged.
 */
interface Merge<T> {
    fun merge(other: T): T
}

/**
 * Returns the first non-blank string, or null if both are blank.
 *
 * @param first The first string to check.
 * @param second The second string to check.
 * @return The first non-blank string, or null.
 */
private fun firstNotBlank(
    first: String?,
    second: String?
): String? {
    if (first.isNullOrBlank()) {
        return second
    }
    return first
}

/**
 * Merges two maps of mergeable values, combining values with the same key.
 *
 * @param K The type of map keys.
 * @param V The type of map values that implement Merge.
 * @param other The other map to merge with.
 * @return A new map containing the merged values.
 */
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
