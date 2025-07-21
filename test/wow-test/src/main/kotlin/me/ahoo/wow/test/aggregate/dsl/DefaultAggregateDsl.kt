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
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.naming.annotation.toName
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.test.AggregateVerifier.aggregateVerifier
import me.ahoo.wow.test.aggregate.ExpectStage
import me.ahoo.wow.test.aggregate.ExpectedResult
import me.ahoo.wow.test.aggregate.GivenStage
import me.ahoo.wow.test.aggregate.VerifiedStage
import me.ahoo.wow.test.aggregate.WhenStage
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest

class DefaultAggregateDsl<C : Any, S : Any>(private val commandAggregateType: Class<C>) :
    AggregateDsl<S>, AbstractDynamicTestBuilder() {
    override fun given(block: GivenDsl<S>.() -> Unit) {
        val givenStage = commandAggregateType.aggregateVerifier<C, S>()
        val givenDsl = DefaultGivenDsl(givenStage)
        block(givenDsl)
        dynamicNodes.addAll(givenDsl.dynamicNodes)
    }
}

abstract class AbstractGivenStageDsl<S : Any> : Decorator<GivenStage<S>>, GivenDsl<S>, AbstractDynamicTestBuilder() {
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
        val container = DynamicContainer.dynamicContainer(
            "Given Events${events.map { it.javaClass.toName() }.toJsonString()}",
            whenDsl.dynamicNodes
        )
        dynamicNodes.add(container)
    }

    override fun givenState(state: S, version: Int, block: WhenDsl<S>.() -> Unit) {
        val whenStage = delegate.givenState(state, version)
        val whenDsl = DefaultWhenDsl(whenStage)
        block(whenDsl)
        val container = DynamicContainer.dynamicContainer("Given[State]", whenDsl.dynamicNodes)
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
        val container = DynamicContainer.dynamicContainer("Given[Empty]", whenDsl.dynamicNodes)
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
        val container = DynamicContainer.dynamicContainer("Given[Verified Stage]", whenDsl.dynamicNodes)
        dynamicNodes.add(container)
    }
}

class DefaultWhenDsl<S : Any>(private val delegate: WhenStage<S>) : WhenDsl<S>, AbstractDynamicTestBuilder() {
    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String,
        block: ExpectDsl<S>.() -> Unit
    ) {
        val expectStage = delegate.whenCommand(command, header, ownerId)
        val expectDsl = DefaultExpectDsl(expectStage)
        block(expectDsl)
        val dynamicTest = DynamicTest.dynamicTest("When[${command.javaClass.toName()}]") {
            expectStage.verify()
        }
        dynamicNodes.add(dynamicTest)
        dynamicNodes.addAll(expectDsl.dynamicNodes)
    }
}

class DefaultExpectDsl<S : Any>(override val delegate: ExpectStage<S>) :
    ExpectStage<S> by delegate,
    Decorator<ExpectStage<S>>,
    ExpectDsl<S>,
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
}
