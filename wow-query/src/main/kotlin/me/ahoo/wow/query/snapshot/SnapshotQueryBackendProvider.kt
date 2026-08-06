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

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the storage backend bound to one aggregate.
 *
 * Gateways and tail filters share this provider so a non-caching custom factory is still created exactly once for an
 * aggregate and the gateway metadata always describes the backend that executes the query.
 */
interface SnapshotQueryBackendProvider {
    fun <S : Any> get(namedAggregate: NamedAggregate): SnapshotQueryService<S>
}

class CachingSnapshotQueryBackendProvider(
    private val backendFactory: SnapshotQueryServiceFactory,
) : SnapshotQueryBackendProvider {
    private val backendCache = ConcurrentHashMap<NamedAggregate, SnapshotQueryService<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> get(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
        return backendCache.computeIfAbsent(namedAggregate.materialize()) {
            backendFactory.create<Any>(it)
        } as SnapshotQueryService<S>
    }
}
