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
import java.util.SortedMap

/**
 * 包深度排序
 */
object ScopeComparator : Comparator<String> {
    override fun compare(o1: String, o2: String): Int {
        val o1Size = dotSize(o1)
        val o2Size = dotSize(o2)
        var comparedValue = o2Size.compareTo(o1Size)
        if (comparedValue != 0) {
            return comparedValue
        }
        comparedValue = o2.length.compareTo(o1.length)
        if (comparedValue != 0) {
            return comparedValue
        }
        return o2.compareTo(o1)
    }

    private fun dotSize(value: String): Int {
        return value.split('.').size
    }
}

interface ScopeSearcher<V : Any> : SortedMap<String, V> {

    fun search(scope: String): V? {
        this[scope]?.let {
            return it
        }

        forEach {
            if (scope.startsWith(it.key)) {
                return it.value
            }
        }
        return null
    }

    fun requiredSearch(scope: String): V {
        return checkNotNull(search(scope)) { "Scope:[$scope] does not have a matching metadata definition." }
    }
}

class ScopeContextSearcher(
    private val source: SortedMap<String, NamedBoundedContext>,
) : ScopeSearcher<NamedBoundedContext>, SortedMap<String, NamedBoundedContext> by source

class ScopeNamedAggregateSearcher(private val source: SortedMap<String, NamedAggregate>) :
    ScopeSearcher<NamedAggregate>,
    SortedMap<String, NamedAggregate> by source

fun WowMetadata.asScopeContextSearcher(): ScopeContextSearcher {
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

fun WowMetadata.asScopeNamedAggregateSearcher(): ScopeNamedAggregateSearcher {
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
