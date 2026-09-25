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

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.cache.StateToCacheDataConverter
import me.ahoo.wow.query.asInProcessQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.query
import reactor.core.publisher.Mono

/**
 * Loads a snapshot by aggregate id through the gateway as a trusted [in-process][asInProcessQuery] query: the cached
 * value is shared by every caller, so it is loaded without any caller's scope or entry. Do not serve a scoped (for
 * example HTTP) read from this cache; the caller's tenant, owner and space would not apply.
 */
@JvmDefaultWithoutCompatibility
open class QueryGatewayCacheSource<S : Any, D : Any>(
    private val snapshotQueryGateway: SnapshotQueryGateway<S>,
    override val stateToCacheDataConverter: StateToCacheDataConverter<MaterializedSnapshot<S>, D>,
    override val loadCacheSourceConfiguration: LoadCacheSourceConfiguration = LoadCacheSourceConfiguration.DEFAULT
) : StateCacheSource<String, MaterializedSnapshot<S>, D> {

    override fun loadState(key: String): Mono<MaterializedSnapshot<S>> {
        return singleQuery {
            filter {
                aggregateId(key)
            }
        }.query(snapshotQueryGateway).asInProcessQuery()
    }
}
