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

package me.ahoo.wow.benchmark.runtime

import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.toMessageFunction
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata

typealias BoundSourcingFunction<S> = MessageFunction<S, DomainEventExchange<*>, Void>

interface SourcingFunctionRegistryVariant<S : Any> {
    operator fun get(eventType: Class<*>): BoundSourcingFunction<S>?

    val cachedFunctionCount: Int
}

/**
 * Exact production baseline: bind every sourcing function while constructing the aggregate.
 */
class EagerSourcingFunctionRegistry<S : Any>(
    metadata: StateAggregateMetadata<S>,
    state: S
) : SourcingFunctionRegistryVariant<S> {
    private val registry = metadata.toMessageFunctionRegistry(state)

    override fun get(eventType: Class<*>): BoundSourcingFunction<S>? = registry[eventType]

    override val cachedFunctionCount: Int
        get() = registry.size
}

/**
 * Adaptive binding with the current map-first cache policy.
 */
class AdaptiveMapFirstSourcingFunctionRegistry<S : Any>(
    private val metadata: StateAggregateMetadata<S>,
    private val state: S
) : SourcingFunctionRegistryVariant<S> {
    private var cache: MutableMap<Class<*>, BoundSourcingFunction<S>>? = null

    override fun get(eventType: Class<*>): BoundSourcingFunction<S>? {
        cache?.get(eventType)?.let {
            return it
        }
        val function = metadata.sourcingFunctionRegistry[eventType]
            ?.toMessageFunction<S, DomainEventExchange<*>, Void>(state)
            ?: return null
        val currentCache = cache ?: HashMap<Class<*>, BoundSourcingFunction<S>>(1).also {
            cache = it
        }
        currentCache[eventType] = function
        return function
    }

    override val cachedFunctionCount: Int
        get() = cache?.size ?: 0
}

private data class SingleSourcingFunctionEntry<S : Any>(
    val eventType: Class<*>,
    val function: BoundSourcingFunction<S>
)

/**
 * Benchmark candidate: bind only an event type observed by this aggregate instance.
 */
class AdaptiveSourcingFunctionRegistry<S : Any>(
    private val metadata: StateAggregateMetadata<S>,
    private val state: S
) : SourcingFunctionRegistryVariant<S> {
    private var singleEntry: SingleSourcingFunctionEntry<S>? = null
    private var cache: MutableMap<Class<*>, BoundSourcingFunction<S>>? = null

    override fun get(eventType: Class<*>): BoundSourcingFunction<S>? {
        val currentCache = cache
        if (currentCache != null) {
            currentCache[eventType]?.let {
                return it
            }
            val function = metadata.sourcingFunctionRegistry[eventType]
                ?.toMessageFunction<S, DomainEventExchange<*>, Void>(state)
                ?: return null
            currentCache[eventType] = function
            return function
        }
        val entry = singleEntry
        if (entry != null && entry.eventType === eventType) {
            return entry.function
        }
        val function = metadata.sourcingFunctionRegistry[eventType]
            ?.toMessageFunction<S, DomainEventExchange<*>, Void>(state)
            ?: return null
        if (entry == null) {
            singleEntry = SingleSourcingFunctionEntry(eventType, function)
            return function
        }
        val promoted = HashMap<Class<*>, BoundSourcingFunction<S>>(4)
        promoted[entry.eventType] = entry.function
        promoted[eventType] = function
        cache = promoted
        singleEntry = null
        return function
    }

    override val cachedFunctionCount: Int
        get() = cache?.size ?: if (singleEntry == null) 0 else 1

    val promoted: Boolean
        get() = cache != null
}
