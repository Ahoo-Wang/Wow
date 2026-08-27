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

package me.ahoo.wow.compiler.metadata

import me.ahoo.wow.compiler.metadata.BoundedContextSearcher.Companion.toBoundedContextSearcher
import me.ahoo.wow.configuration.Aggregate
import me.ahoo.wow.configuration.BoundedContext
import me.ahoo.wow.configuration.ScopeComparator
import me.ahoo.wow.configuration.ScopeSearcher
import me.ahoo.wow.configuration.WowMetadata
import java.util.*

class WowMetadataMerger {
    var metadata: WowMetadata = WowMetadata()
        private set

    fun merge(other: WowMetadata) {
        metadata = metadata.merge(other)
    }

    fun merge(aggregateName: String, aggregate: Aggregate) {
        val boundedContextSearcher = metadata.toBoundedContextSearcher()
        val matchedContext = boundedContextSearcher.requiredSearch(aggregate.type!!)
        val contextName = matchedContext.first
        val contextAlias = matchedContext.second.alias
        val aggregateContext = BoundedContext(
            alias = contextAlias,
            aggregates = mapOf(aggregateName to aggregate),
        )

        val aggregateMetadata = WowMetadata(contexts = mapOf(contextName to aggregateContext))
        merge(aggregateMetadata)
    }
}

internal class BoundedContextSearcher(
    private val source: SortedMap<String, Pair<String, BoundedContext>>
) : ScopeSearcher<Pair<String, BoundedContext>>, SortedMap<String, Pair<String, BoundedContext>> by source {
    companion object {
        fun WowMetadata.toBoundedContextSearcher(): BoundedContextSearcher {
            val source = mutableMapOf<String, Pair<String, BoundedContext>>().apply {
                contexts.forEach { contextEntry ->
                    contextEntry.value.aggregates.flatMap { it.value.scopes }
                        .plus(contextEntry.value.scopes)
                        .toSet()
                        .forEach {
                            put(it, contextEntry.key to contextEntry.value)
                        }
                }
            }.toSortedMap(ScopeComparator)
            return BoundedContextSearcher(source)
        }
    }
}
