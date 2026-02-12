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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.serialization.JsonSerializer

/**
 * The resource name for Wow metadata JSON files.
 * Metadata files are loaded from this classpath resource location.
 */
const val WOW_METADATA_RESOURCE_NAME = "META-INF/wow-metadata.json"

/**
 * Central searcher for Wow metadata and aggregate configurations.
 * Loads and caches metadata from classpath resources, providing various search capabilities
 * for bounded contexts, aggregates, and their mappings.
 */
object MetadataSearcher {
    private val log = KotlinLogging.logger {}

    /**
     * Lazily loaded Wow metadata aggregated from all classpath resources.
     * Merges metadata from multiple sources found at WOW_METADATA_RESOURCE_NAME.
     */
    val metadata: WowMetadata by lazy {
        var current = WowMetadata()
        ClassLoader.getSystemResources(WOW_METADATA_RESOURCE_NAME)
            .iterator()
            .forEach { resource ->
                log.debug {
                    "Load metadata [$resource]."
                }
                @Suppress("TooGenericExceptionCaught")
                resource.openStream().use {
                    try {
                        val next = JsonSerializer.readValue(it, WowMetadata::class.java)
                        current = current.merge(next)
                    } catch (e: Throwable) {
                        log.error(e) { e.message }
                    }
                }
            }
        current
    }

    /**
     * Lazily loaded searcher for finding named aggregates by their type classes.
     */
    val typeNamedAggregate: TypeNamedAggregateSearcher by lazy {
        metadata.toTypeNamedAggregateSearcher()
    }

    /**
     * Lazily loaded searcher for finding aggregate types by their named aggregates.
     */
    val namedAggregateType: NamedAggregateTypeSearcher by lazy {
        metadata.toNamedAggregateTypeSearcher()
    }

    /**
     * Lazily loaded searcher for finding bounded contexts by package scopes.
     */
    val scopeContext: ScopeContextSearcher by lazy {
        metadata.toScopeContextSearcher()
    }

    /**
     * Lazily loaded searcher for finding named aggregates by package scopes.
     */
    val scopeNamedAggregate: ScopeNamedAggregateSearcher by lazy {
        metadata.toScopeNamedAggregateSearcher()
    }

    /**
     * Lazily loaded set of all local aggregates available at runtime.
     */
    val localAggregates: Set<NamedAggregate> by lazy {
        namedAggregateType.keys.map { it.materialize() }.toSet()
    }

    /**
     * Checks if the named aggregate is available locally at runtime.
     *
     * @return true if the aggregate is local, false otherwise.
     */
    fun NamedAggregate.isLocal(): Boolean = localAggregates.contains(this.materialize())

    /**
     * Retrieves the aggregate configuration for the given named aggregate.
     *
     * @param namedAggregate The named aggregate to look up.
     * @return The aggregate configuration or null if not found.
     */
    fun getAggregate(namedAggregate: NamedAggregate): Aggregate? =
        metadata.contexts[namedAggregate.contextName]?.aggregates?.get(namedAggregate.aggregateName)

    /**
     * Retrieves the aggregate configuration for the given named aggregate, throwing an exception if not found.
     *
     * @param namedAggregate The named aggregate to look up.
     * @return The aggregate configuration.
     * @throws IllegalArgumentException if the named aggregate configuration is not found.
     */
    fun requiredAggregate(namedAggregate: NamedAggregate): Aggregate =
        requireNotNull(getAggregate(namedAggregate)) {
            "NamedAggregate configuration [$namedAggregate] not found."
        }
}

/**
 * Finds the named bounded context associated with this class based on its package scope.
 *
 * @param T The type of the class.
 * @return The named bounded context or null if not found.
 */
fun <T> Class<T>.namedBoundedContext(): NamedBoundedContext? = MetadataSearcher.scopeContext.search(name)

/**
 * Finds the named bounded context associated with this class, throwing an exception if not found.
 *
 * @param T The type of the class.
 * @return The named bounded context.
 * @throws IllegalStateException if no matching bounded context is found.
 */
fun <T> Class<T>.requiredNamedBoundedContext(): NamedBoundedContext = MetadataSearcher.scopeContext.requiredSearch(name)

/**
 * Finds the named aggregate associated with this class based on its package scope.
 *
 * @param T The type of the class.
 * @return The named aggregate or null if not found.
 */
fun <T> Class<T>.namedAggregate(): NamedAggregate? = MetadataSearcher.scopeNamedAggregate.search(name)

/**
 * Finds the named aggregate associated with this class, throwing an exception if not found.
 *
 * @param T The type of the class.
 * @return The named aggregate.
 * @throws IllegalStateException if no matching named aggregate is found.
 */
fun <T> Class<T>.requiredNamedAggregate(): NamedAggregate = MetadataSearcher.scopeNamedAggregate.requiredSearch(name)

/**
 * Finds the aggregate type class associated with this named aggregate.
 *
 * @param T The expected type of the aggregate class.
 * @return The aggregate class or null if not found.
 */
fun <T> NamedAggregate.aggregateType(): Class<T>? {
    @Suppress("UNCHECKED_CAST")
    return MetadataSearcher.namedAggregateType[this.materialize()] as Class<T>?
}

/**
 * Finds the aggregate type class associated with this named aggregate, throwing an exception if not found.
 *
 * @param T The expected type of the aggregate class.
 * @return The aggregate class.
 * @throws IllegalStateException if the aggregate type is not found.
 */
fun <T> NamedAggregate.requiredAggregateType(): Class<T> =
    checkNotNull(aggregateType()) {
        "NamedAggregate [$this] not found."
    }

/**
 * Finds the named aggregate for the reified type T.
 *
 * @param T The type to find the named aggregate for.
 * @return The named aggregate or null if not found.
 */
inline fun <reified T> namedAggregate(): NamedAggregate? = T::class.java.namedAggregate()

/**
 * Finds the named aggregate for the reified type T, throwing an exception if not found.
 *
 * @param T The type to find the named aggregate for.
 * @return The named aggregate.
 * @throws IllegalStateException if no matching named aggregate is found.
 */
inline fun <reified T> requiredNamedAggregate(): NamedAggregate = T::class.java.requiredNamedAggregate()
