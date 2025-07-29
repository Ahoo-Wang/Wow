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

package me.ahoo.wow.test.saga.stateless.dsl

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.saga.stateless.CommandStream
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.dsl.NameSpecCapable.Companion.appendName
import me.ahoo.wow.test.saga.stateless.CommandIterator
import me.ahoo.wow.test.saga.stateless.ExpectStage
import me.ahoo.wow.test.saga.stateless.ExpectedResult
import me.ahoo.wow.test.saga.stateless.WhenStage
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import kotlin.reflect.KClass

class DefaultStatelessSagaDsl<T : Any>(private val processorType: Class<T>) : StatelessSagaDsl<T>,
    AbstractDynamicTestBuilder() {
    override val publicServiceProvider: ServiceProvider = SimpleServiceProvider()

    override fun on(
        serviceProvider: ServiceProvider,
        commandGateway: CommandGateway,
        commandMessageFactory: CommandMessageFactory,
        block: WhenDsl<T>.() -> Unit
    ) {
        publicServiceProvider.copyTo(serviceProvider)
        val whenStage = processorType.sagaVerifier(
            serviceProvider = serviceProvider,
            commandGateway = commandGateway,
            commandMessageFactory = commandMessageFactory
        )
        val whenDsl = DefaultWhenDsl(whenStage)
        block(whenDsl)
        dynamicNodes.addAll(whenDsl.dynamicNodes)
    }
}

class DefaultWhenDsl<T : Any>(override val delegate: WhenStage<T>) :
    Decorator<WhenStage<T>>,
    WhenDsl<T>,
    AbstractDynamicTestBuilder(),
    Named {
    override var name: String = ""
        private set

    override fun name(name: String) {
        this.name = name
    }

    override fun inject(inject: ServiceProvider.() -> Unit) {
        delegate.inject(inject)
    }

    override fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean) {
        delegate.functionFilter(filter)
    }

    override fun whenEvent(
        event: Any,
        state: Any?,
        ownerId: String,
        block: ExpectDsl<T>.() -> Unit
    ) {
        val expectStage = delegate.whenEvent(event, state, ownerId)
        val expectDsl = DefaultExpectDsl(expectStage)
        block(expectDsl)
        val displayName = buildString {
            append("When ")
            if (state != null) {
                append("State")
            }
            append(" Event")
            append("[${event.javaClass.simpleName}]")
            appendName(name)
        }
        val dynamicTest = DynamicContainer.dynamicContainer(displayName, expectDsl.dynamicNodes)
        dynamicNodes.add(dynamicTest)
    }
}

class DefaultExpectDsl<T : Any>(override val delegate: ExpectStage<T>) : Decorator<ExpectStage<T>>, ExpectDsl<T>,
    AbstractDynamicTestBuilder() {
    override fun expect(expected: ExpectedResult<T>.() -> Unit): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("Expect") {
            delegate.verify(false).expect(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectCommandStream(expected: CommandStream.() -> Unit): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectCommandStream") {
            delegate.verify(false).expectCommandStream(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectCommandIterator(expected: CommandIterator.() -> Unit): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectCommandIterator") {
            delegate.verify(false).expectCommandIterator(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectNoCommand(): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectNoCommand") {
            delegate.verify(false).expectNoCommand()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun <C : Any> expectCommand(expected: CommandMessage<C>.() -> Unit): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectCommand") {
            delegate.verify(false).expectCommand(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun <C : Any> expectCommandBody(expected: C.() -> Unit): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectCommandBody") {
            delegate.verify(false).expectCommandBody(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectCommandCount(expected: Int): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectCommandCount[$expected]") {
            delegate.verify(false).expectCommandCount(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectCommandType(vararg expected: KClass<*>): ExpectDsl<T> {
        val dynamicTest =
            DynamicTest.dynamicTest("ExpectCommandType[${expected.joinToString(",") { it.simpleName!! }}]") {
                delegate.verify(false).expectCommandType(*expected)
            }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectNoError(): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectNoError") {
            delegate.verify(false).expectNoError()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun expectError(): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectError") {
            delegate.verify(false).expectError()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun <E : Throwable> expectError(expected: E.() -> Unit): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectError") {
            delegate.verify(false).expectError(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    override fun <E : Throwable> expectErrorType(expected: KClass<E>): ExpectDsl<T> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectErrorType[${expected.simpleName}]") {
            delegate.verify(false).expectErrorType(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }
}
