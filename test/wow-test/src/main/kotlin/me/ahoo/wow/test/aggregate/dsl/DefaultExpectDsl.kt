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
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.test.aggregate.EventIterator
import me.ahoo.wow.test.aggregate.ExpectStage
import me.ahoo.wow.test.aggregate.ExpectedResult
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.dsl.NameSpecCapable.Companion.appendName
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import java.util.function.Consumer
import kotlin.reflect.KClass

class DefaultExpectDsl<S : Any>(override val delegate: ExpectStage<S>) :
    ExpectDsl<S>,
    Decorator<ExpectStage<S>>,
    AbstractDynamicTestBuilder() {

    override fun fork(
        name: String,
        verifyError: Boolean,
        block: ForkedVerifiedStageDsl<S>.() -> Unit
    ) {
        val displayName = buildString {
            append("Fork")
            appendName(name)
        }
        val forkNode = try {
            val verifiedStage = delegate.verify().fork(verifyError)
            val forkedVerifiedStageDsl = DefaultForkedVerifiedStageDsl(verifiedStage)
            block(forkedVerifiedStageDsl)
            DynamicContainer.dynamicContainer(displayName, forkedVerifiedStageDsl.dynamicNodes)
        } catch (e: Throwable) {
            DynamicTest.dynamicTest("$displayName Error") {
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
