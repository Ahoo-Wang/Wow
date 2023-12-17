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
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.serialization.JsonSerializer
import org.slf4j.LoggerFactory

const val WOW_METADATA_RESOURCE_NAME = "META-INF/wow-metadata.json"

object MetadataSearcher {
    private val log = LoggerFactory.getLogger(MetadataSearcher::class.java)
    val metadata: WowMetadata by lazy {
        var current = WowMetadata()
        ClassLoader.getSystemResources(WOW_METADATA_RESOURCE_NAME)
            .iterator()
            .forEach { resource ->
                if (log.isDebugEnabled) {
                    log.debug("Load metadata [{}].", resource)
                }
                @Suppress("TooGenericExceptionCaught")
                resource.openStream().use {
                    try {
                        val next = JsonSerializer.readValue(it, WowMetadata::class.java)
                        current = current.merge(next)
                    } catch (e: Throwable) {
                        if (log.isErrorEnabled) {
                            log.error(e.message, e)
                        }
                    }
                }
            }
        current
    }

    val typeNamedAggregate: TypeNamedAggregateSearcher by lazy {
        metadata.toTypeNamedAggregateSearcher()
    }
    val namedAggregateType: NamedAggregateTypeSearcher by lazy {
        metadata.toNamedAggregateTypeSearcher()
    }
    val scopeContext: ScopeContextSearcher by lazy {
        metadata.toScopeContextSearcher()
    }
    val scopeNamedAggregate: ScopeNamedAggregateSearcher by lazy {
        metadata.toScopeNamedAggregateSearcher()
    }

    val localAggregates: Set<NamedAggregate> by lazy {
        namedAggregateType.keys.map { it.materialize() }.toSet()
    }

    fun NamedAggregate.isLocal(): Boolean {
        return localAggregates.contains(this.materialize())
    }

    fun getAggregate(namedAggregate: NamedAggregate): Aggregate? {
        return metadata.contexts[namedAggregate.contextName]?.aggregates?.get(namedAggregate.aggregateName)
    }

    fun requiredAggregate(namedAggregate: NamedAggregate): Aggregate {
        return requireNotNull(getAggregate(namedAggregate)) {
            "NamedAggregate configuration [$namedAggregate] not found."
        }
    }
}

fun <T> Class<T>.namedBoundedContext(): NamedBoundedContext? {
    return MetadataSearcher.scopeContext.search(name)
}

fun <T> Class<T>.requiredNamedBoundedContext(): NamedBoundedContext {
    return MetadataSearcher.scopeContext.requiredSearch(name)
}

fun <T> Class<T>.namedAggregate(): NamedAggregate? {
    return MetadataSearcher.scopeNamedAggregate.search(name)
}

fun <T> Class<T>.requiredNamedAggregate(): NamedAggregate {
    return MetadataSearcher.scopeNamedAggregate.requiredSearch(name)
}

fun <T> NamedAggregate.aggregateType(): Class<T>? {
    @Suppress("UNCHECKED_CAST")
    return MetadataSearcher.namedAggregateType[this.materialize()] as Class<T>?
}

fun <T> NamedAggregate.requiredAggregateType(): Class<T> {
    return checkNotNull(aggregateType()) {
        "NamedAggregate [$this] not found."
    }
}

inline fun <reified T> namedAggregate(): NamedAggregate? {
    return T::class.java.namedAggregate()
}

inline fun <reified T> requiredNamedAggregate(): NamedAggregate {
    return T::class.java.requiredNamedAggregate()
}
