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

package me.ahoo.wow.test.aggregate.dsl

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.test.aggregate.AggregateExpecter
import me.ahoo.wow.test.aggregate.ExpectedResult
import me.ahoo.wow.test.dsl.NameSpecCapable
import me.ahoo.wow.test.saga.stateless.dsl.InjectPublicServiceCapable

interface AggregateDsl<S : Any> : InjectPublicServiceCapable {
    fun on(
        aggregateId: String = generateGlobalId(),
        tenantId: String = TenantId.DEFAULT_TENANT_ID,
        stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
        eventStore: EventStore = InMemoryEventStore(),
        serviceProvider: ServiceProvider = SimpleServiceProvider(),
        block: GivenDsl<S>.() -> Unit
    )
}

interface GivenDsl<S : Any> : WhenDsl<S>, NameSpecCapable {
    fun inject(inject: ServiceProvider.() -> Unit)

    fun givenOwnerId(ownerId: String)
    fun givenEvent(event: Any, block: WhenDsl<S>.() -> Unit)
    fun givenEvent(events: Array<out Any> = emptyArray(), block: WhenDsl<S>.() -> Unit)

    fun givenState(state: S, version: Int, block: WhenDsl<S>.() -> Unit)
}

interface WhenDsl<S : Any> : NameSpecCapable {
    fun whenCommand(
        command: Any,
        header: Header = DefaultHeader.Companion.empty(),
        ownerId: String = OwnerId.Companion.DEFAULT_OWNER_ID,
        block: ExpectDsl<S>.() -> Unit
    )
}

interface ExpectDsl<S : Any> : AggregateExpecter<S, ExpectDsl<S>> {
    fun fork(
        name: String = "",
        verifyError: Boolean = false,
        block: ForkedVerifiedStageDsl<S>.() -> Unit
    )
}

interface ForkedVerifiedStageDsl<S : Any> : GivenDsl<S> {
    val verifiedResult: ExpectedResult<S>
    val stateAggregate: StateAggregate<S>
        get() = verifiedResult.stateAggregate
    val stateRoot: S
        get() = stateAggregate.state
}
