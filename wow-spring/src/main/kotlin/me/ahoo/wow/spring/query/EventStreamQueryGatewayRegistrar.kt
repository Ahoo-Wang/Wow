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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryGateway
import org.springframework.beans.factory.getBean
import org.springframework.core.ResolvableType

/** Registers each aggregate's [EventStreamQueryGateway]. */
@OptIn(WowSpi::class) // Binds each aggregate's gateway to its routed query backend.
class EventStreamQueryGatewayRegistrar :
    QueryGatewayRegistrar(QueryModel.EVENT_STREAM, EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME) {
    companion object {
        /** Bean name for the event stream observer shared by aggregate gateways; also used for custom overrides. */
        const val EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME = "eventStreamQueryObserver"
    }

    override fun gatewayBeanName(namedAggregate: NamedAggregate): String =
        namedAggregate.eventStreamQueryGatewayBeanName()

    override fun gatewayDefinition(
        namedAggregate: MaterializedNamedAggregate,
        aggregateType: Class<*>,
    ): QueryGatewayDefinition = QueryGatewayDefinition(
        type = ResolvableType.forClass(EventStreamQueryGateway::class.java),
    ) { beanFactory, collaborators ->
        DefaultEventStreamQueryGateway(
            namedAggregate = namedAggregate,
            backend = beanFactory.getBean<EventStreamQueryBackendFactory>().create(namedAggregate).backend,
            schemaProvider = collaborators.schemaProvider,
            filters = collaborators.filters,
            policies = collaborators.policies,
            observer = collaborators.observer,
            entryPolicy = collaborators.entryPolicy,
        )
    }
}
