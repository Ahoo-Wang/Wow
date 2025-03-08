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
import me.ahoo.cache.api.Cache
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.cache.CacheValueConfiguration
import me.ahoo.wow.cache.StateToCacheDataConverter
import me.ahoo.wow.event.StateDomainEventExchange

/**
 * 主动刷新缓存.
 */
@JvmDefaultWithoutCompatibility
open class SetStateCacheRefresher<S : Any, D>(
    namedAggregate: NamedAggregate,
    private val stateToCacheDataConverter: StateToCacheDataConverter<S, D>,
    override val cache: Cache<String, D>,
    override val ttl: Long? = null,
    override val amplitude: Long = 0
) : CacheValueConfiguration, StateCacheRefresher<S, D, StateDomainEventExchange<S, Any>>(namedAggregate) {

    override val functionKind: FunctionKind = FunctionKind.STATE_EVENT
    override fun refresh(exchange: StateDomainEventExchange<S, Any>) {
        if (exchange.state.deleted) {
            cache.evict(exchange.message.aggregateId.id)
            return
        }
        val cacheData = stateToCacheDataConverter.stateToCacheData(exchange.state.state)
        val ttl = ttl
        val cacheValue = if (ttl == null) {
            DefaultCacheValue.forever(cacheData)
        } else {
            DefaultCacheValue.ttlAt(cacheData, ttl, amplitude)
        }
        cache.setCache(exchange.message.aggregateId.id, cacheValue)
    }
}
