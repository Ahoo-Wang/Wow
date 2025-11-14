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

/**
 * Default implementation of the ExpectDsl interface for defining aggregate test expectations.
 *
 * This class provides methods to specify various expectations about the outcome of aggregate
 * operations, including state changes, events, and error conditions. It wraps an ExpectStage
 * and converts expectations into JUnit 5 dynamic tests that can be executed dynamically.
 *
 * @param S the state type of the aggregate being tested
 * @property delegate the underlying ExpectStage that performs the actual verification logic
 */
class DefaultExpectDsl<S : Any>(
    override val delegate: ExpectStage<S>
) : AbstractDynamicTestBuilder(),
    ExpectDsl<S>,
    Decorator<ExpectStage<S>> {
    /**
     * Creates a forked verification path for testing alternative scenarios.
     *
     * This method allows branching the test execution to verify different outcomes
     * or error conditions from the same initial state. The fork creates a new
     * verification context that can be configured independently.
     *
     * @param name descriptive name for the forked test branch, used in test reporting
     * @param verifyError whether to verify error conditions in this fork (true) or success conditions (false)
     * @param block the forked verification logic using ForkedVerifiedStageDsl
     */
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

    /**
     * Defines expectations for the complete test result.
     *
     * This method allows specifying comprehensive expectations about the aggregate's
     * final state, events produced, and any errors that occurred during command processing.
     *
     * @param expected lambda function defining the expected result properties
     * @return this ExpectDsl instance for method chaining
     */
    override fun expect(expected: ExpectedResult<S>.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("Expect") {
            delegate.verify(false).expect(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectations for the aggregate's complete state including metadata.
     *
     * This method verifies the aggregate's state object along with its version,
     * aggregate ID, and other aggregate-level properties.
     *
     * @param expected lambda function defining the expected state aggregate properties
     * @return this ExpectDsl instance for method chaining
     */
    override fun expectStateAggregate(expected: StateAggregate<S>.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectStateAggregate") {
            delegate.verify(false).expectStateAggregate(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectations for the aggregate's state object.
     *
     * This method verifies the business state of the aggregate after command processing,
     * allowing checks on the domain-specific properties of the state.
     *
     * @param expected lambda function defining the expected state properties
     * @return this ExpectDsl instance for method chaining
     */
    override fun expectState(expected: S.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectState") {
            delegate.verify(false).expectState(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectations for the aggregate's state using a Consumer.
     *
     * This is a convenience overload that accepts a Java Consumer for defining
     * state expectations, useful when integrating with Java-based test code.
     *
     * @param expected Consumer defining the expected state properties
     * @return this ExpectDsl instance for method chaining
     */
    override fun expectState(expected: Consumer<S>): ExpectDsl<S> = expectState {
        expected.accept(this)
    }

    /**
     * Defines expectations for the complete domain event stream.
     *
     * This method verifies the entire sequence of domain events produced by the aggregate,
     * including event metadata like sequence numbers, timestamps, and headers.
     *
     * @param expected lambda function defining the expected event stream properties
     * @return this ExpectDsl instance for method chaining
     */
    override fun expectEventStream(expected: DomainEventStream.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEventStream") {
            delegate.verify(false).expectEventStream(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectations for iterating through domain events.
     *
     * This method provides an iterator-based approach to verify domain events,
     * useful for checking event sequences or performing complex event validations.
     *
     * @param expected lambda function defining the expected event iterator behavior
     * @return this ExpectDsl instance for method chaining
     */
    override fun expectEventIterator(expected: EventIterator.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEventIterator") {
            delegate.verify(false).expectEventIterator(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectations for a specific domain event.
     *
     * This method verifies properties of a single domain event, including its body,
     * metadata, and header information.
     *
     * @param E the type of the event body
     * @param expected lambda function defining the expected event properties
     * @return this ExpectDsl instance for method chaining
     */
    override fun <E : Any> expectEvent(expected: DomainEvent<E>.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEvent") {
            delegate.verify(false).expectEvent(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectations for a domain event's body content.
     *
     * This method focuses specifically on verifying the business data contained
     * within a domain event, ignoring metadata like headers and timestamps.
     *
     * @param E the type of the event body
     * @param expected lambda function defining the expected event body properties
     * @return this ExpectDsl instance for method chaining
     */
    override fun <E : Any> expectEventBody(expected: E.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEventBody") {
            delegate.verify(false).expectEventBody(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectations for the number of domain events produced.
     *
     * This method verifies that the aggregate produced exactly the expected number
     * of domain events during command processing.
     *
     * @param expected the expected number of events
     * @return this ExpectDsl instance for method chaining
     */
    override fun expectEventCount(expected: Int): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectEventCount[$expected]") {
            delegate.verify(false).expectEventCount(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectations for the types of domain events produced.
     *
     * This method verifies that the aggregate produced events of the specified types,
     * in the order they are provided.
     *
     * @param expected variable number of event type classes to expect
     * @return this ExpectDsl instance for method chaining
     */
    override fun expectEventType(vararg expected: KClass<*>): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest(
            "ExpectEventType[${
                expected.joinToString(",") {
                    it.simpleName!!
                }
            }]",
        ) {
            delegate.verify(false).expectEventType(*expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectation that no error occurred during command processing.
     *
     * This method verifies that the aggregate command executed successfully
     * without throwing any exceptions.
     *
     * @return this ExpectDsl instance for method chaining
     */
    override fun expectNoError(): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectNoError") {
            delegate.verify(false).expectNoError()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectation that an error occurred during command processing.
     *
     * This method verifies that the aggregate command failed and threw an exception.
     *
     * @return this ExpectDsl instance for method chaining
     */
    override fun expectError(): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectError") {
            delegate.verify(false).expectError()
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectations for a specific error that occurred.
     *
     * This method verifies properties of the exception that was thrown during
     * command processing, such as message, type, or custom properties.
     *
     * @param E the expected exception type
     * @param expected lambda function defining the expected error properties
     * @return this ExpectDsl instance for method chaining
     */
    override fun <E : Throwable> expectError(expected: E.() -> Unit): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectError") {
            delegate.verify(false).expectError(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }

    /**
     * Defines expectation for the type of error that occurred.
     *
     * This method verifies that the exception thrown during command processing
     * is of the specified type.
     *
     * @param E the expected exception type
     * @param expected the class of the expected exception
     * @return this ExpectDsl instance for method chaining
     */
    override fun <E : Throwable> expectErrorType(expected: KClass<E>): ExpectDsl<S> {
        val dynamicTest = DynamicTest.dynamicTest("ExpectErrorType[${expected.simpleName}]") {
            delegate.verify(false).expectErrorType(expected)
        }
        dynamicNodes.add(dynamicTest)
        return this
    }
}
