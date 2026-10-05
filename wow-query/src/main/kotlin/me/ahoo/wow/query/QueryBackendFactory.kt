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

package me.ahoo.wow.query

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import java.util.concurrent.ConcurrentHashMap

/** Pairs a query backend of read model [B] with its storage adapter, for each aggregate. */
@WowSpi
fun interface QueryBackendFactory<out B : QueryBackend> {
    fun create(namedAggregate: NamedAggregate): QueryBackendBinding<B>
}

/** A [QueryBackendFactory] that creates each aggregate's binding once and reuses it. */
@WowSpi
abstract class AbstractQueryBackendFactory<B : QueryBackend> : QueryBackendFactory<B> {
    private val bindingCache = ConcurrentHashMap<MaterializedNamedAggregate, QueryBackendBinding<B>>()

    override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<B> =
        bindingCache.computeIfAbsent(namedAggregate.materialize(), ::createBinding)

    protected abstract fun createBinding(namedAggregate: NamedAggregate): QueryBackendBinding<B>
}

/** Routes each aggregate to the factory of its route, or to [defaultFactory]. */
@WowSpi
open class RoutingQueryBackendFactory<B : QueryBackend>(
    private val defaultFactory: QueryBackendFactory<B>,
    routes: Map<NamedAggregate, QueryBackendFactory<B>>,
) : QueryBackendFactory<B> {
    private val routes: Map<MaterializedNamedAggregate, QueryBackendFactory<B>> =
        routes.mapKeys { (namedAggregate, _) -> namedAggregate.materialize() }

    override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<B> =
        (routes[namedAggregate.materialize()] ?: defaultFactory).create(namedAggregate)
}
