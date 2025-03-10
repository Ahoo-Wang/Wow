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

package me.ahoo.wow.cache.source

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.wow.cache.StateToCacheDataConverter
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

interface StateCacheSource<K, S : Any, D : Any> : CacheSource<K, D> {
    val loadCacheSourceConfiguration: LoadCacheSourceConfiguration
        get() = LoadCacheSourceConfiguration.DEFAULT
    val stateToCacheDataConverter: StateToCacheDataConverter<S, D>
    fun loadState(key: K): Mono<S>

    override fun loadCacheValue(key: K): CacheValue<D>? {
        val state = loadState(key).map {
            stateToCacheDataConverter.stateToCacheData(it)
        }.toFuture()
            .get(loadCacheSourceConfiguration.timeout.toMillis(), TimeUnit.MILLISECONDS)
            ?: return null
        val ttl = loadCacheSourceConfiguration.ttl ?: return DefaultCacheValue.forever(state)
        return DefaultCacheValue.ttlAt(state, ttl, loadCacheSourceConfiguration.amplitude)
    }
}
