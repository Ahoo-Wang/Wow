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
package me.ahoo.wow.test

import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.test.AggregateVerifier.asAggregateVerifier
import me.ahoo.wow.test.aggregate.DefaultGivenStage
import me.ahoo.wow.test.aggregate.GivenStage

/**
 * Aggregate Verifier .
 * 聚合测试套件 by Given/When/Expect.
 *
 * @author ahoo wang
 */
object AggregateVerifier {

    @JvmStatic
    @JvmOverloads
    fun <C : Any, S : Any> Class<C>.asAggregateVerifier(
        aggregateId: String = GlobalIdGenerator.generateAsString(),
        tenantId: String = TenantId.DEFAULT_TENANT_ID,
        stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
        eventStore: EventStore = InMemoryEventStore(),
        serviceProvider: ServiceProvider = SimpleServiceProvider()
    ): GivenStage<S> {
        val metadata: AggregateMetadata<C, S> = asAggregateMetadata()
        return DefaultGivenStage(
            metadata.asAggregateId(
                id = aggregateId,
                tenantId = tenantId,
            ),
            metadata,
            stateAggregateFactory,
            SimpleCommandAggregateFactory(eventStore),
            serviceProvider,
        )
    }
}

inline fun <reified C : Any, S : Any> aggregateVerifier(
    aggregateId: String = GlobalIdGenerator.generateAsString(),
    tenantId: String = TenantId.DEFAULT_TENANT_ID
): GivenStage<S> {
    return C::class.java.asAggregateVerifier(
        aggregateId = aggregateId,
        tenantId = tenantId,
    )
}

inline fun <reified C : Any, S : Any> aggregateVerifier(
    aggregateId: String = GlobalIdGenerator.generateAsString(),
    tenantId: String = TenantId.DEFAULT_TENANT_ID,
    serviceProvider: ServiceProvider
): GivenStage<S> {
    return C::class.java.asAggregateVerifier(
        aggregateId = aggregateId,
        tenantId = tenantId,
        serviceProvider = serviceProvider,
    )
}

inline fun <reified C : Any, S : Any> aggregateVerifier(
    aggregateId: String = GlobalIdGenerator.generateAsString(),
    tenantId: String = TenantId.DEFAULT_TENANT_ID,
    eventStore: EventStore = InMemoryEventStore(),
    serviceProvider: ServiceProvider = SimpleServiceProvider()
): GivenStage<S> {
    return C::class.java.asAggregateVerifier(
        aggregateId = aggregateId,
        tenantId = tenantId,
        eventStore = eventStore,
        serviceProvider = serviceProvider,
    )
}
