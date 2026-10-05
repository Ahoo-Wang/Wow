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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import java.util.*

/**
 * Scope searcher for finding named bounded contexts by package scopes.
 * Maps package name prefixes to their corresponding bounded contexts.
 *
 * @param source The sorted map of scope strings to named bounded contexts.
 */
class ScopeContextSearcher(
    private val source: SortedMap<String, NamedBoundedContext>
) : ScopeSearcher<NamedBoundedContext>,
    SortedMap<String, NamedBoundedContext> by source

/**
 * Scope searcher for finding named aggregates by package scopes.
 * Maps package name prefixes to their corresponding named aggregates.
 *
 * @param source The sorted map of scope strings to named aggregates.
 */
class ScopeNamedAggregateSearcher(
    private val source: SortedMap<String, NamedAggregate>
) : ScopeSearcher<NamedAggregate>,
    SortedMap<String, NamedAggregate> by source

/**
 * Converts WowMetadata to a ScopeContextSearcher.
 * Builds a mapping from package scopes to named bounded contexts.
 *
 * @return A ScopeContextSearcher with the scope mappings.
 */
fun WowMetadata.toScopeContextSearcher(): ScopeContextSearcher {
    val source = mutableMapOf<String, NamedBoundedContext>().apply {
        contexts.forEach { contextEntry ->
            val contextName = contextEntry.key
            contextEntry.value.aggregates.flatMap { it.value.scopes }
                .plus(contextEntry.value.scopes)
                .toSet()
                .forEach {
                    put(it, MaterializedNamedBoundedContext(contextName))
                }
        }
    }.toSortedMap(ScopeComparator)
    return ScopeContextSearcher(source)
}

/**
 * Converts WowMetadata to a ScopeNamedAggregateSearcher.
 * Builds a mapping from package scopes to named aggregates based on aggregate scopes, commands, and events.
 *
 * @return A ScopeNamedAggregateSearcher with the scope mappings.
 */
fun WowMetadata.toScopeNamedAggregateSearcher(): ScopeNamedAggregateSearcher {
    val source = mutableMapOf<String, NamedAggregate>().apply {
        contexts.forEach { contextEntry ->
            val contextName = contextEntry.key
            contextEntry.value.aggregates.forEach { aggregateEntry ->
                val aggregateName = aggregateEntry.key
                val namedAggregate = MaterializedNamedAggregate(contextName, aggregateName)
                aggregateEntry.value.scopes.forEach { scope ->
                    put(scope, namedAggregate)
                }
                aggregateEntry.value.commands.forEach { scope ->
                    put(scope, namedAggregate)
                }
                aggregateEntry.value.events.forEach { scope ->
                    put(scope, namedAggregate)
                }
            }
        }
    }.toSortedMap(ScopeComparator)
    return ScopeNamedAggregateSearcher(source)
}
