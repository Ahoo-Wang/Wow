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

package me.ahoo.wow.cache

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.Cache
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.event.StateDomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Mono

/**
 * 主动刷新缓存.
 */
@JvmDefaultWithoutCompatibility
open class StateCacheRefresher<S : Any, D>(
    override val namedAggregate: NamedAggregate,
    private val stateToCacheDataConverter: StateToCacheDataConverter<S, D>,
    private val cache: Cache<String, D>,
    override val ttl: Long? = null,
    override val amplitude: Long = 0,
    private val mode: RefreshMode = RefreshMode.EVICT
) : NamedAggregateDecorator,
    CacheValueConfiguration,
    MessageFunction<StateCacheRefresher<S, D>, StateDomainEventExchange<S, Any>, Mono<Void>> {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(StateCacheRefresher::class.java)
    }

    override val functionKind: FunctionKind = FunctionKind.STATE_EVENT
    override val name: String = StateCacheRefresher<*, *>::invoke.name
    override val processor: StateCacheRefresher<S, D> = this
    override val supportedTopics: Set<NamedAggregate> = setOf(namedAggregate.materialize())
    override val supportedType: Class<*> = Any::class.java

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? {
        return null
    }

    override fun invoke(exchange: StateDomainEventExchange<S, Any>): Mono<Void> {
        return Mono.fromRunnable {
            if (log.isDebugEnabled) {
                log.debug("[$mode]Refresh {} Cache.", exchange.state.aggregateId)
            }
            when (mode) {
                RefreshMode.EVICT -> evictRefresh(exchange)
                RefreshMode.SET -> setRefresh(exchange)
            }
        }
    }

    private fun evictRefresh(exchange: StateDomainEventExchange<S, Any>) {
        cache.evict(exchange.state.aggregateId.id)
    }

    private fun setRefresh(exchange: StateDomainEventExchange<S, Any>) {
        if (exchange.state.deleted) {
            evictRefresh(exchange)
            return
        }
        val cacheData = stateToCacheDataConverter.stateToCacheData(exchange.state.state)
        val ttl = ttl
        val cacheValue = if (ttl == null) {
            DefaultCacheValue.forever(cacheData)
        } else {
            DefaultCacheValue.ttlAt(cacheData, ttl, amplitude)
        }
        cache.setCache(exchange.state.aggregateId.id, cacheValue)
    }
}
