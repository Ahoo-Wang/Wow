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

package me.ahoo.wow.test.aggregate

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.naming.annotation.toName
import me.ahoo.wow.test.dsl.InjectServiceCapable
import kotlin.reflect.KType
import kotlin.reflect.full.defaultType

interface GivenStage<S : Any> : InjectServiceCapable<GivenStage<S>> {
    fun <SERVICE : Any> inject(
        service: SERVICE,
        serviceName: String = service.javaClass.toName(),
        serviceType: KType = service.javaClass.kotlin.defaultType
    ): GivenStage<S> {
        inject {
            register(service, serviceName, serviceType)
        }
        return this
    }

    fun givenOwnerId(ownerId: String): GivenStage<S>

    /**
     * 1. 给定领域事件，朔源聚合.
     */
    fun given(vararg events: Any): WhenStage<S> {
        return givenEvent(*events)
    }

    fun givenEvent(vararg events: Any): WhenStage<S>

    fun givenState(state: S, version: Int): WhenStage<S>

    fun givenState(state: StateAggregate<S>): WhenStage<S>
}

fun <S : Any> GivenStage<S>.whenCommand(
    command: Any,
    header: Header = DefaultHeader.empty(),
    ownerId: String = OwnerId.DEFAULT_OWNER_ID
): ExpectStage<S> {
    return this.givenEvent().whenCommand(command, header, ownerId)
}

fun <S : Any> GivenStage<S>.`when`(
    command: Any,
    header: Header = DefaultHeader.empty(),
    ownerId: String = OwnerId.DEFAULT_OWNER_ID
): ExpectStage<S> {
    return this.whenCommand(command, header, ownerId)
}

internal abstract class AbstractGivenStage<C : Any, S : Any> : GivenStage<S> {
    abstract val aggregateId: AggregateId
    abstract val metadata: AggregateMetadata<C, S>
    abstract val stateAggregateFactory: StateAggregateFactory
    abstract val commandAggregateFactory: CommandAggregateFactory
    abstract val serviceProvider: ServiceProvider
    protected var ownerId: String = OwnerId.DEFAULT_OWNER_ID
        private set

    override fun inject(inject: ServiceProvider.() -> Unit): GivenStage<S> {
        inject(serviceProvider)
        return this
    }

    override fun givenOwnerId(ownerId: String): GivenStage<S> {
        this.ownerId = ownerId
        return this
    }

    override fun givenEvent(vararg events: Any): WhenStage<S> {
        return DefaultWhenStage(
            aggregateId = aggregateId,
            ownerId = ownerId,
            events = events,
            metadata = metadata,
            stateAggregateFactory = stateAggregateFactory,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
        )
    }

    override fun givenState(state: S, version: Int): WhenStage<S> {
        val stateAggregate = metadata.toStateAggregate(
            state = state,
            version = version,
            ownerId = ownerId,
            aggregateId = aggregateId.id,
            tenantId = aggregateId.tenantId
        )
        return givenState(stateAggregate)
    }

    override fun givenState(state: StateAggregate<S>): WhenStage<S> {
        return GivenStateWhenStage(
            metadata = metadata,
            stateAggregate = state,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
        )
    }
}

internal class DefaultGivenStage<C : Any, S : Any>(
    override val aggregateId: AggregateId,
    override val metadata: AggregateMetadata<C, S>,
    override val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
    override val commandAggregateFactory: CommandAggregateFactory,
    override val serviceProvider: ServiceProvider
) : AbstractGivenStage<C, S>()
