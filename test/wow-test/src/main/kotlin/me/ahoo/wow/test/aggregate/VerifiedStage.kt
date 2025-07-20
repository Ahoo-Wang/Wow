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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Copyable
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.serialization.deepCody
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject

interface VerifiedStage<S : Any> : GivenStage<S> {
    val verifiedResult: ExpectedResult<S>
    val stateAggregate: StateAggregate<S>
        get() = verifiedResult.stateAggregate
    val stateRoot: S
        get() = stateAggregate.state

    fun then(verifyError: Boolean = false): VerifiedStage<S>

    /**
     * 为当前环境创建一个完全独立的测试分支上下文
     */
    fun fork(
        verifyError: Boolean = false,
        serviceProviderSupplier: (ServiceProvider) -> ServiceProvider = {
            require(it is Copyable<*>)
            it.copy() as ServiceProvider
        },
        commandAggregateFactorySupplier: () -> CommandAggregateFactory = {
            SimpleCommandAggregateFactory(InMemoryEventStore())
        },
        handle: VerifiedStage<S>.() -> Unit
    ): VerifiedStage<S>
}

internal fun <S : Any> verifyStateAggregateSerializable(stateAggregate: StateAggregate<S>): StateAggregate<S> {
    if (!stateAggregate.initialized) {
        return stateAggregate
    }
    val serialized = stateAggregate.toJsonString()
    val deserialized = serialized.toObject<StateAggregate<S>>()
    deserialized.assert().isEqualTo(stateAggregate)
    return deserialized
}

internal class DefaultVerifiedStage<C : Any, S : Any>(
    override val verifiedResult: ExpectedResult<S>,
    override val metadata: AggregateMetadata<C, S>,
    override val commandAggregateFactory: CommandAggregateFactory,
    override val serviceProvider: ServiceProvider
) : VerifiedStage<S>, AbstractGivenStage<C, S>() {
    override val aggregateId: AggregateId
        get() = verifiedResult.stateAggregate.aggregateId

    override val stateAggregateFactory: StateAggregateFactory
        get() = object : StateAggregateFactory {
            override fun <S : Any> create(
                metadata: StateAggregateMetadata<S>,
                aggregateId: AggregateId
            ): StateAggregate<S> {
                @Suppress("UNCHECKED_CAST")
                return verifiedResult.stateAggregate as StateAggregate<S>
            }
        }

    override fun then(verifyError: Boolean): VerifiedStage<S> {
        verifyError(verifyError)
        return this
    }

    private fun verifyError(verifyError: Boolean) {
        if (verifyError) {
            require(!verifiedResult.hasError) {
                "An exception[${verifiedResult.error}] occurred in the verified result."
            }
        }
    }

    override fun fork(
        verifyError: Boolean,
        serviceProviderSupplier: (ServiceProvider) -> ServiceProvider,
        commandAggregateFactorySupplier: () -> CommandAggregateFactory,
        handle: VerifiedStage<S>.() -> Unit
    ): VerifiedStage<S> {
        verifyError(verifyError)
        val forkedStateAggregate = verifyStateAggregateSerializable(verifiedResult.stateAggregate)
        val forkedEventStream = verifiedResult.domainEventStream?.deepCody(DomainEventStream::class.java)
        val forkedResult = verifiedResult.copy(
            stateAggregate = forkedStateAggregate,
            domainEventStream = forkedEventStream
        )
        val forkedServiceProvider = serviceProviderSupplier(serviceProvider)
        val forkedCommandAggregateFactory = commandAggregateFactorySupplier()
        val forkedVerifiedStage = DefaultVerifiedStage(
            verifiedResult = forkedResult,
            metadata = this.metadata,
            commandAggregateFactory = forkedCommandAggregateFactory,
            serviceProvider = forkedServiceProvider,
        )
        handle(forkedVerifiedStage)
        return this
    }
}
