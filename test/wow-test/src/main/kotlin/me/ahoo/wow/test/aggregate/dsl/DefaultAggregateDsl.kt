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

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.test.AggregateVerifier.aggregateVerifier
import me.ahoo.wow.test.aggregate.EventIterator
import me.ahoo.wow.test.aggregate.ExpectStage
import me.ahoo.wow.test.aggregate.ExpectedResult
import me.ahoo.wow.test.aggregate.GivenStage
import me.ahoo.wow.test.aggregate.VerifiedStage
import me.ahoo.wow.test.aggregate.WhenStage
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import java.util.function.Consumer
import kotlin.reflect.KClass

class DefaultAggregateDsl<C : Any, S : Any>(private val commandAggregateType: Class<C>) :
    AggregateDsl<S>, AbstractDynamicTestBuilder() {
    override fun on(
        aggregateId: String,
        tenantId: String,
        stateAggregateFactory: StateAggregateFactory,
        eventStore: EventStore,
        serviceProvider: ServiceProvider,
        block: GivenDsl<S>.() -> Unit
    ) {
        val givenStage = commandAggregateType.aggregateVerifier<C, S>(
            aggregateId = aggregateId,
            tenantId = tenantId,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            serviceProvider = serviceProvider
        )
        val givenDsl = DefaultGivenDsl(givenStage)
        block(givenDsl)
        dynamicNodes.addAll(givenDsl.dynamicNodes)
    }
}

abstract class AbstractGivenStageDsl<S : Any> : Decorator<GivenStage<S>>, GivenDsl<S>, Named,
    AbstractDynamicTestBuilder() {
    final override var name: String = ""
        private set

    override fun name(name: String) {
        this.name = name
    }

    override fun inject(inject: ServiceProvider.() -> Unit) {
        delegate.inject(inject)
    }

    override fun givenOwnerId(ownerId: String) {
        delegate.givenOwnerId(ownerId)
    }

    override fun givenEvent(event: Any, block: WhenDsl<S>.() -> Unit) {
        givenEvent(arrayOf(event), block)
    }

    override fun givenEvent(events: Array<out Any>, block: WhenDsl<S>.() -> Unit) {
        val whenStage = delegate.givenEvent(*events)
        val whenDsl = DefaultWhenDsl(whenStage)
        block(whenDsl)

        val displayName = buildString {
            append("Given")
            append(" Events[${events.joinToString(",") { it.javaClass.simpleName }}]")
            if (name.isNotEmpty()) {
                append("[$name]")
            }
        }
        val container = DynamicContainer.dynamicContainer(
            displayName,
            whenDsl.dynamicNodes
        )
        dynamicNodes.add(container)
    }

    override fun givenState(state: S, version: Int, block: WhenDsl<S>.() -> Unit) {
        val whenStage = delegate.givenState(state, version)
        val whenDsl = DefaultWhenDsl(whenStage)
        block(whenDsl)
        val displayName = buildString {
            append("Given[State]")
            if (name.isNotEmpty()) {
                append("[$name]")
            }
        }
        val container = DynamicContainer.dynamicContainer(displayName, whenDsl.dynamicNodes)
        dynamicNodes.add(container)
    }

    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String,
        block: ExpectDsl<S>.() -> Unit
    ) {
        val givenStage = delegate.givenEvent()
        val whenDsl = DefaultWhenDsl(givenStage)
        whenDsl.whenCommand(command, header, ownerId, block)
        val displayName = buildString {
            append("Given[Empty]")
            if (name.isNotEmpty()) {
                append("[$name]")
            }
        }
        val container = DynamicContainer.dynamicContainer(displayName, whenDsl.dynamicNodes)
        dynamicNodes.add(container)
    }
}

class DefaultGivenDsl<S : Any>(
    override val delegate: GivenStage<S>
) : AbstractGivenStageDsl<S>()

class DefaultForkedVerifiedStageDsl<S : Any>(override val delegate: VerifiedStage<S>) : ForkedVerifiedStageDsl<S>,
    AbstractGivenStageDsl<S>() {
    override val verifiedResult: ExpectedResult<S>
        get() = delegate.verifiedResult

    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String,
        block: ExpectDsl<S>.() -> Unit
    ) {
        val givenStage = delegate.givenEvent()
        val whenDsl = DefaultWhenDsl(givenStage)
        whenDsl.whenCommand(command, header, ownerId, block)
        val displayName = buildString {
            append("Given[Verified Stage]")
            if (name.isNotEmpty()) {
                append("[$name]")
            }
        }
        val container = DynamicContainer.dynamicContainer(displayName, whenDsl.dynamicNodes)
        dynamicNodes.add(container)
    }
}

class DefaultWhenDsl<S : Any>(private val delegate: WhenStage<S>) : WhenDsl<S>, Named, AbstractDynamicTestBuilder() {
    override var name: String = ""
        private set

    override fun name(name: String) {
        this.name = name
    }

    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String,
        block: ExpectDsl<S>.() -> Unit
    ) {
        val expectStage = delegate.whenCommand(command, header, ownerId)
        val expectDsl = DefaultExpectDsl(expectStage)
        block(expectDsl)
        val displayName = buildString {
            append("When[${command.javaClass.simpleName}]")
            if (name.isNotEmpty()) {
                append("[$name]")
            }
        }
        val dynamicTest = DynamicContainer.dynamicContainer(displayName, expectDsl.dynamicNodes)
        dynamicNodes.add(dynamicTest)
    }
}

class DefaultExpectDsl<S : Any>(override val delegate: ExpectStage<S>) :
    ExpectDsl<S>,
    Decorator<ExpectStage<S>>,
    AbstractDynamicTestBuilder() {
    override fun fork(
        verifyError: Boolean,
        block: ForkedVerifiedStageDsl<S>.() -> Unit
    ) {
        val forkNode = try {
            val verifiedStage = delegate.verify().fork(verifyError)
            val forkedVerifiedStageDsl = DefaultForkedVerifiedStageDsl(verifiedStage)
            block(forkedVerifiedStageDsl)
            DynamicContainer.dynamicContainer("Fork", forkedVerifiedStageDsl.dynamicNodes)
        } catch (e: Throwable) {
            DynamicTest.dynamicTest("Fork Error") {
                throw e
            }
        }
        dynamicNodes.add(forkNode)
    }

    override fun expect(expected: ExpectedResult<S>.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("Expect") {
            delegate.verify(false).expect(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectStateAggregate(expected: StateAggregate<S>.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectStateAggregate") {
            delegate.verify(false).expectStateAggregate(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectState(expected: S.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectState") {
            delegate.verify(false).expectState(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectState(expected: Consumer<S>): ExpectDsl<S> {
        return expectState {
            expected.accept(this)
        }
    }

    override fun expectEventStream(expected: DomainEventStream.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEventStream") {
            delegate.verify(false).expectEventStream(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectEventIterator(expected: EventIterator.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEventIterator") {
            delegate.verify(false).expectEventIterator(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun <E : Any> expectEvent(expected: DomainEvent<E>.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEvent") {
            delegate.verify(false).expectEvent(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun <E : Any> expectEventBody(expected: E.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEventBody") {
            delegate.verify(false).expectEventBody(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectEventCount(expected: Int): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEventCount[$expected]") {
            delegate.verify(false).expectEventCount(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectEventType(vararg expected: KClass<*>): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest(
            "ExpectEventType[${
                expected.joinToString(",") {
                    it.simpleName!!
                }
            }]"
        ) {
            delegate.verify(false).expectEventType(*expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectNoError(): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectNoError") {
            delegate.verify(false).expectNoError()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectError(): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectError") {
            delegate.verify(false).expectError()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun <E : Throwable> expectError(expected: E.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectError") {
            delegate.verify(false).expectError(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun <E : Throwable> expectErrorType(expected: KClass<E>): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectErrorType[${expected.simpleName}]") {
            delegate.verify(false).expectErrorType(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }
}
