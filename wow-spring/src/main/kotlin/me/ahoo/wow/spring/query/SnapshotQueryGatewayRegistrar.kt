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

package me.ahoo.wow.spring.query

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.serialization.JsonSerializer
import org.springframework.beans.factory.getBean
import org.springframework.core.ResolvableType

/** Registers each aggregate's [SnapshotQueryGateway], typed by its state so it can be injected by generics. */
@OptIn(WowSpi::class) // Binds each aggregate's gateway to its routed query backend.
class SnapshotQueryGatewayRegistrar :
    QueryGatewayRegistrar(QueryModel.SNAPSHOT, SNAPSHOT_QUERY_OBSERVER_BEAN_NAME) {
    companion object {
        /** Bean name for the snapshot observer shared by aggregate gateways; also used for custom overrides. */
        const val SNAPSHOT_QUERY_OBSERVER_BEAN_NAME = "snapshotQueryObserver"
    }

    override fun gatewayBeanName(namedAggregate: NamedAggregate): String =
        namedAggregate.snapshotQueryGatewayBeanName()

    override fun gatewayDefinition(
        namedAggregate: MaterializedNamedAggregate,
        aggregateType: Class<*>,
    ): QueryGatewayDefinition {
        val stateType = aggregateType.aggregateMetadata<Any, Any>().state.aggregateType
        return QueryGatewayDefinition(
            type = ResolvableType.forClassWithGenerics(SnapshotQueryGateway::class.java, stateType),
        ) { beanFactory, collaborators ->
            DefaultSnapshotQueryGateway<Any>(
                namedAggregate = namedAggregate,
                backend = beanFactory.getBean<SnapshotQueryBackendFactory>().create(namedAggregate).backend,
                schemaProvider = collaborators.schemaProvider,
                targetType = JsonSerializer.typeFactory.constructParametricType(
                    MaterializedSnapshot::class.java,
                    stateType,
                ),
                filters = collaborators.filters,
                policies = collaborators.policies,
                observer = collaborators.observer,
                entryPolicy = collaborators.entryPolicy,
            )
        }
    }
}
