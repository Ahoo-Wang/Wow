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
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize

class RoutingSnapshotQueryBackendFactory(
    private val defaultFactory: SnapshotQueryBackendFactory,
    routes: Map<NamedAggregate, SnapshotQueryBackendFactory>,
) : SnapshotQueryBackendFactory {
    private val routes: Map<MaterializedNamedAggregate, SnapshotQueryBackendFactory> =
        routes.mapKeys { (namedAggregate, _) -> namedAggregate.materialize() }

    override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryBackend =
        (routes[namedAggregate.materialize()] ?: defaultFactory).create<S>(namedAggregate)
}
