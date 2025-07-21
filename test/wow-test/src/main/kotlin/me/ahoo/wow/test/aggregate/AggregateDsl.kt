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
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.test.AggregateVerifier.aggregateVerifier
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest

interface DynamicTestBuilder {
    val dynamicNodes: List<DynamicNode>
}

abstract class AbstractDynamicTestBuilder : DynamicTestBuilder {
    override val dynamicNodes: MutableList<DynamicNode> = mutableListOf()
}

class AggregateDsl<C : Any, S : Any>(private val commandAggregateType: Class<C>) :
    AbstractDynamicTestBuilder() {
    fun given(displayName: String = "Empty", block: GivenDsl<S>.() -> Unit) {
        val givenStage = commandAggregateType.aggregateVerifier<C, S>()
        val givenDsl = GivenDsl(givenStage)
        block(givenDsl)
        val dynamicTest = DynamicContainer.dynamicContainer("Given : $displayName", givenDsl.dynamicNodes)
        dynamicNodes.add(dynamicTest)
    }
}


abstract class AbstractGivenStageDsl<S : Any> : Decorator<GivenStage<S>>, AbstractDynamicTestBuilder() {
    fun inject(inject: ServiceProvider.() -> Unit) {
        delegate.inject(inject)
    }

    fun givenOwnerId(ownerId: String) {
        delegate.givenOwnerId(ownerId)
    }

    private fun whenDsl(whenStage: WhenStage<S>, block: WhenDsl<S>.() -> Unit) {
        val whenDsl = WhenDsl(whenStage)
        block(whenDsl)
        dynamicNodes.addAll(whenDsl.dynamicNodes)
    }

    fun givenEvent(events: Array<out Any> = emptyArray(), block: WhenDsl<S>.() -> Unit) {
        val whenStage = delegate.givenEvent(*events)
        whenDsl(whenStage, block)
    }

    fun givenState(state: S, version: Int, block: WhenDsl<S>.() -> Unit) {
        val whenStage = delegate.givenState(state, version)
        whenDsl(whenStage, block)
    }

    fun whenCommand(
        command: Any,
        header: Header = DefaultHeader.Companion.empty(),
        ownerId: String = OwnerId.Companion.DEFAULT_OWNER_ID,
        block: ExpectDsl<S>.() -> Unit
    ) {
        val givenStage = delegate.givenEvent()
        val whenDsl = WhenDsl(givenStage)
        whenDsl.whenCommand(command, header, ownerId, block)
        dynamicNodes.addAll(whenDsl.dynamicNodes)
    }
}

class GivenDsl<S : Any>(
    override val delegate: GivenStage<S>
) : AbstractGivenStageDsl<S>()

class ForkedVerifiedStageDsl<S : Any>(override val delegate: VerifiedStage<S>) : AbstractGivenStageDsl<S>()

class WhenDsl<S : Any>(private val delegate: WhenStage<S>) : AbstractDynamicTestBuilder() {
    fun whenCommand(
        command: Any,
        header: Header = DefaultHeader.Companion.empty(),
        ownerId: String = OwnerId.Companion.DEFAULT_OWNER_ID,
        block: ExpectDsl<S>.() -> Unit
    ) {
        val expectStage = delegate.whenCommand(command, header, ownerId)
        val expectDsl = ExpectDsl(expectStage)
        block(expectDsl)
        val dynamicTest = DynamicTest.dynamicTest("Expect") {
            expectStage.verify()
        }
        dynamicNodes.add(dynamicTest)
        dynamicNodes.addAll(expectDsl.dynamicNodes)
    }
}

class ExpectDsl<S : Any>(override val delegate: ExpectStage<S>) : ExpectStage<S> by delegate, Decorator<ExpectStage<S>>,
    AbstractDynamicTestBuilder() {
    fun fork(
        verifyError: Boolean = false,
        block: ForkedVerifiedStageDsl<S>.() -> Unit
    ) {
        val verifiedStage = delegate.verify().fork(verifyError)
        val forkedVerifiedStageDsl = ForkedVerifiedStageDsl(verifiedStage)
        block(forkedVerifiedStageDsl)
        val forkNode = DynamicContainer.dynamicContainer("Fork", forkedVerifiedStageDsl.dynamicNodes)
        dynamicNodes.add(forkNode)
    }
}