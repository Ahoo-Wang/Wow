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
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize

private val log = KotlinLogging.logger("me.ahoo.wow.configuration.AggregateSearcher")

interface AggregateSearcher

/**
 * aggregateType -> NamedAggregate
 */
class TypeNamedAggregateSearcher(private val source: Map<Class<*>, NamedAggregate>) :
    AggregateSearcher,
    Map<Class<*>, NamedAggregate> by source

/**
 * NamedAggregate -> aggregateType
 */
class NamedAggregateTypeSearcher(private val source: Map<MaterializedNamedAggregate, Class<*>>) :
    AggregateSearcher,
    Map<MaterializedNamedAggregate, Class<*>> by source

fun WowMetadata.toTypeNamedAggregateSearcher(): TypeNamedAggregateSearcher {
    val source = mutableMapOf<Class<*>, NamedAggregate>().apply {
        contexts.forEach { contextEntry ->
            val contextName = contextEntry.key
            contextEntry
                .value
                .aggregates
                .forEach aggregateForEach@{ aggregateEntry ->
                    val aggregateName = aggregateEntry.key
                    val aggregateTypeName = aggregateEntry.value.type
                    if (aggregateTypeName.isNullOrBlank()) {
                        return@aggregateForEach
                    }
                    try {
                        val aggregateType = Class.forName(aggregateEntry.value.type)
                        put(aggregateType, MaterializedNamedAggregate(contextName, aggregateName))
                    } catch (e: ClassNotFoundException) {
                        log.warn(e) {
                            "Aggregate type[$aggregateTypeName] not found at current runtime, ignore the aggregate."
                        }
                    }
                }
        }
    }
    return TypeNamedAggregateSearcher(source)
}

fun WowMetadata.toNamedAggregateTypeSearcher(): NamedAggregateTypeSearcher {
    val source = toTypeNamedAggregateSearcher().map {
        it.value.materialize() to it.key
    }.toMap()
    return NamedAggregateTypeSearcher(source)
}
