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
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.saga.stateless.CommandStream
import me.ahoo.wow.test.captureDynamicTest
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.saga.stateless.CommandIterator
import me.ahoo.wow.test.saga.stateless.ExpectStage
import me.ahoo.wow.test.saga.stateless.ExpectedResult
import org.junit.jupiter.api.DynamicTest
import kotlin.reflect.KClass

/**
 * Default implementation of [ExpectDsl] that creates dynamic JUnit 5 tests for each expectation.
 *
 * This class wraps an [ExpectStage] and converts each expectation method call into
 * a [DynamicTest] that can be executed by JUnit 5's dynamic test framework.
 * Each expectation is verified without immediately throwing exceptions, allowing
 * for better test reporting.
 *
 * @param T The type of the saga being tested.
 * @property delegate The underlying expect stage that performs the actual verification.
 */
class DefaultExpectDsl<T : Any>(
    override val delegate: ExpectStage<T>
) : AbstractDynamicTestBuilder(),
    Decorator<ExpectStage<T>>,
    ExpectDsl<T> {
    /**
     * Creates a dynamic test for custom expectations on the expected result.
     *
     * @param expected A lambda function defining expectations on the [ExpectedResult].
     * @return This DSL instance for method chaining.
     */
    override fun expect(expected: ExpectedResult<T>.() -> Unit): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("Expect") {
            delegate.verify(false).expect(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test for expectations on the command stream.
     *
     * @param expected A lambda function defining expectations on the [CommandStream].
     * @return This DSL instance for method chaining.
     */
    override fun expectCommandStream(expected: CommandStream.() -> Unit): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectCommandStream") {
            delegate.verify(false).expectCommandStream(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test for expectations on a command iterator.
     *
     * @param expected A lambda function defining expectations on the [CommandIterator].
     * @return This DSL instance for method chaining.
     */
    override fun expectCommandIterator(expected: CommandIterator.() -> Unit): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectCommandIterator") {
            delegate.verify(false).expectCommandIterator(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test expecting no commands to be generated.
     *
     * @return This DSL instance for method chaining.
     */
    override fun expectNoCommand(): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectNoCommand") {
            delegate.verify(false).expectNoCommand()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test for expectations on the first command.
     *
     * @param C The type of the command body.
     * @param expected A lambda function defining expectations on the first [CommandMessage].
     * @return This DSL instance for method chaining.
     */
    override fun <C : Any> expectCommand(expected: CommandMessage<C>.() -> Unit): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectCommand") {
            delegate.verify(false).expectCommand(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test for expectations on the first command's body.
     *
     * @param C The type of the command body.
     * @param expected A lambda function defining expectations on the command body.
     * @return This DSL instance for method chaining.
     */
    override fun <C : Any> expectCommandBody(expected: C.() -> Unit): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectCommandBody") {
            delegate.verify(false).expectCommandBody(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test expecting a specific number of commands.
     *
     * @param expected The expected number of commands in the command stream.
     * @return This DSL instance for method chaining.
     */
    override fun expectCommandCount(expected: Int): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectCommandCount[$expected]") {
            delegate.verify(false).expectCommandCount(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test expecting commands of specific types in order.
     *
     * @param expected Variable number of [KClass] representing expected command body types.
     * @return This DSL instance for method chaining.
     */
    override fun expectCommandType(vararg expected: KClass<*>): ExpectDsl<T> {
        val dynamicTest =
            captureDynamicTest("ExpectCommandType[${expected.joinToString(",") { it.simpleName!! }}]") {
                delegate.verify(false).expectCommandType(*expected)
            }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test expecting no error occurred.
     *
     * @return This DSL instance for method chaining.
     */
    override fun expectNoError(): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectNoError") {
            delegate.verify(false).expectNoError()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test expecting an error occurred.
     *
     * @return This DSL instance for method chaining.
     */
    override fun expectError(): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectError") {
            delegate.verify(false).expectError()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test for expectations on a specific error.
     *
     * @param E The type of the expected error.
     * @param expected A lambda function defining expectations on the error.
     * @return This DSL instance for method chaining.
     */
    override fun <E : Throwable> expectError(expected: E.() -> Unit): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectError") {
            delegate.verify(false).expectError(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Creates a dynamic test expecting an error of a specific type.
     *
     * @param E The type of the expected error.
     * @param expected The [KClass] of the expected error type.
     * @return This DSL instance for method chaining.
     */
    override fun <E : Throwable> expectErrorType(expected: KClass<E>): ExpectDsl<T> {
        val dynamicTest = captureDynamicTest("ExpectErrorType[${expected.simpleName}]") {
            delegate.verify(false).expectErrorType(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }
}
