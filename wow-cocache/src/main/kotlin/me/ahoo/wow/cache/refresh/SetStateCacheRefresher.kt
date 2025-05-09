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

package me.ahoo.wow.cache.refresh

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.TtlConfiguration
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.cache.StateToCacheDataConverter
import me.ahoo.wow.event.StateDomainEventExchange
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate

/**
 * 主动刷新缓存.
 */
@JvmDefaultWithoutCompatibility
open class SetStateCacheRefresher<K, S : Any, D>(
    namedAggregate: NamedAggregate,
    private val stateToCacheDataConverter: StateToCacheDataConverter<ReadOnlyStateAggregate<S>, D>,
    override val ttl: Long = CoCache.DEFAULT_TTL,
    override val ttlAmplitude: Long = CoCache.DEFAULT_TTL_AMPLITUDE,
    val cache: Cache<K, D>,
    val keyConvert: (StateDomainEventExchange<S, Any>) -> K = { exchange ->
        @Suppress("UNCHECKED_CAST")
        exchange.message.aggregateId.id as K
    }
) : TtlConfiguration, StateCacheRefresher<S, D, StateDomainEventExchange<S, Any>>(namedAggregate) {

    override val functionKind: FunctionKind = FunctionKind.STATE_EVENT

    open fun evict(exchange: StateDomainEventExchange<S, Any>) {
        val key = keyConvert(exchange)
        cache.evict(key)
    }

    override fun refresh(exchange: StateDomainEventExchange<S, Any>) {
        if (exchange.state.deleted) {
            evict(exchange)
            return
        }
        val key = keyConvert(exchange)
        val cacheData = stateToCacheDataConverter.stateToCacheData(exchange.state)
        val cacheValue = DefaultCacheValue.ttlAt(cacheData, ttl, ttlAmplitude)
        cache.setCache(key, cacheValue)
    }
}
