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
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.state.StateAggregate
import java.util.function.Consumer
import kotlin.reflect.KClass

/**
 * Defines the interface for aggregate expectation testing in the WOW framework.
 *
 * This interface provides methods to assert various aspects of aggregate behavior after command execution,
 * including state validation, event stream verification, and error checking. It supports fluent API chaining
 * for building comprehensive test assertions.
 *
 * @param S the type of the aggregate state
 * @param AE the self-referential type for fluent chaining (typically the implementing class)
 */
interface AggregateExpecter<S : Any, AE : AggregateExpecter<S, AE>> {
    /**
     * Applies custom expectations to the test result.
     *
     * This method allows for flexible assertion logic by providing access to the complete ExpectedResult,
     * enabling complex validation scenarios beyond the built-in methods.
     *
     * @param expected a lambda function that receives the ExpectedResult and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun expect(expected: ExpectedResult<S>.() -> Unit): AE

    /**
     * Expects specific conditions on the aggregate state and metadata.
     *
     * This method provides access to the complete StateAggregate object, allowing assertions on
     * aggregate metadata, version, initialization status, and other aggregate-level properties.
     *
     * @param expected a lambda function that receives the StateAggregate and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun expectStateAggregate(expected: StateAggregate<S>.() -> Unit): AE = expect {
        expected(stateAggregate)
    }

    /**
     * Expects specific conditions on the aggregate state object.
     *
     * This method focuses on the state data itself, allowing assertions on the business state
     * properties without access to aggregate metadata.
     *
     * @param expected a lambda function that receives the state object and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun expectState(expected: S.() -> Unit): AE = expectStateAggregate {
        expected(state)
    }

    /**
     * Expects specific conditions on the aggregate state object using a Consumer.
     *
     * This is a Java-friendly overload that accepts a Consumer instead of a lambda,
     * useful when integrating with Java code or when a method reference is preferred.
     *
     * @param expected a Consumer that receives the state object and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun expectState(expected: Consumer<S>): AE = expectState {
        expected.accept(this)
    }

    /**
     * Expects specific conditions on the domain event stream.
     *
     * This method validates the entire event stream produced by command execution,
     * including event count, ordering, and stream metadata.
     *
     * @param expected a lambda function that receives the DomainEventStream and performs assertions
     * @return the current expecter instance for method chaining
     * @throws AssertionError if the domain event stream is null or assertions fail
     */
    fun expectEventStream(expected: DomainEventStream.() -> Unit): AE =
        expect {
            domainEventStream
                .assert()
                .describedAs {
                    buildString {
                        append("Expect the domain event stream is not null.")
                        error?.let { error ->
                            appendLine()
                            append(error.stackTraceToString())
                        }
                    }
                }.isNotNull()
            expected(domainEventStream!!)
        }

    /**
     * Expects specific conditions on the domain event stream using a Consumer.
     *
     * Java-friendly overload for expectEventStream that accepts a Consumer instead of a lambda.
     *
     * @param expected a Consumer that receives the DomainEventStream and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun expectEventStream(expected: Consumer<DomainEventStream>): AE = expectEventStream {
        expected.accept(this)
    }

    /**
     * Expects specific conditions on an event iterator for the domain event stream.
     *
     * This method provides an EventIterator wrapper around the event stream, offering
     * convenient methods for iterating through events with type-safe access.
     *
     * @param expected a lambda function that receives the EventIterator and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun expectEventIterator(expected: EventIterator.() -> Unit): AE = expectEventStream {
        expected(EventIterator((iterator())))
    }

    /**
     * Expects specific conditions on an event iterator using a Consumer.
     *
     * Java-friendly overload for expectEventIterator that accepts a Consumer instead of a lambda.
     *
     * @param expected a Consumer that receives the EventIterator and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun expectEventIterator(expected: Consumer<EventIterator>): AE = expectEventIterator {
        expected.accept(this)
    }

    /**
     * Expects specific conditions on the first domain event in the stream.
     *
     * This method focuses on the first event produced by command execution, allowing
     * detailed assertions on its content, metadata, and type.
     *
     * @param E the type of the event body
     * @param expected a lambda function that receives the first DomainEvent and performs assertions
     * @return the current expecter instance for method chaining
     * @throws AssertionError if the event stream is empty or assertions fail
     */
    fun <E : Any> expectEvent(expected: DomainEvent<E>.() -> Unit): AE = expectEventStream {
        assert()
            .describedAs { "Expect the domain event stream size to be greater than 1." }
            .hasSizeGreaterThanOrEqualTo(1)
        @Suppress("UNCHECKED_CAST")
        expected(first() as DomainEvent<E>)
    }

    /**
     * Expects specific conditions on the first domain event using a Consumer.
     *
     * Java-friendly overload for expectEvent that accepts a Consumer instead of a lambda.
     *
     * @param E the type of the event body
     * @param expected a Consumer that receives the first DomainEvent and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun <E : Any> expectEvent(expected: Consumer<DomainEvent<E>>): AE = expectEvent {
        expected.accept(this)
    }

    /**
     * Expects specific conditions on the body of the first domain event.
     *
     * This method focuses on the business data within the event, allowing assertions
     * on the event payload without concern for event metadata.
     *
     * @param E the type of the event body
     * @param expected a lambda function that receives the event body and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun <E : Any> expectEventBody(expected: E.() -> Unit): AE = expectEvent {
        expected(body)
    }

    /**
     * Expects specific conditions on the body of the first domain event using a Consumer.
     *
     * Java-friendly overload for expectEventBody that accepts a Consumer instead of a lambda.
     *
     * @param E the type of the event body
     * @param expected a Consumer that receives the event body and performs assertions
     * @return the current expecter instance for method chaining
     */
    fun <E : Any> expectEventBody(expected: Consumer<E>): AE = expectEventBody<E> {
        expected.accept(this)
    }

    /**
     * Expects a specific number of events in the domain event stream.
     *
     * This method validates that the command execution produced exactly the expected
     * number of domain events.
     *
     * @param expected the expected number of events in the stream
     * @return the current expecter instance for method chaining
     * @throws AssertionError if the event count doesn't match the expected value
     */
    fun expectEventCount(expected: Int): AE = expectEventStream {
        assert().describedAs { "Expect the domain event stream size." }.hasSize(expected)
    }

    /**
     * Expects events of specific types in the exact order provided.
     *
     * This method validates both the count and the types of events in the stream,
     * ensuring they match the expected sequence exactly.
     *
     * @param expected variable number of KClass objects representing the expected event types in order
     * @return the current expecter instance for method chaining
     * @throws AssertionError if the event types don't match the expected sequence
     */
    fun expectEventType(vararg expected: KClass<*>): AE = expectEventCount(expected.size)
        .expectEventStream {
            val itr = iterator()
            for (eventType in expected) {
                itr
                    .next()
                    .body
                    .assert()
                    .isInstanceOf(eventType.java)
            }
        }

    /**
     * Expects events of specific types in the exact order provided (Java-friendly overload).
     *
     * This method accepts Class objects instead of KClass, making it more convenient
     * for Java integration while maintaining the same validation logic.
     *
     * @param expected variable number of Class objects representing the expected event types in order
     * @return the current expecter instance for method chaining
     * @throws AssertionError if the event types don't match the expected sequence
     */
    fun expectEventType(vararg expected: Class<*>): AE = expectEventType(*expected.map { it.kotlin }.toTypedArray())

    /**
     * Expects that no error occurred during command execution.
     *
     * This method validates that the command was processed successfully without throwing
     * any exceptions or encountering error conditions. It is commonly used to assert
     * that aggregate operations completed normally and produced the expected results.
     *
     * @return the current expecter instance for method chaining
     * @throws AssertionError if an error occurred during command execution
     */
    fun expectNoError(): AE = expect {
        error.assert().describedAs { "Expect no error" }.isNull()
    }

    /**
     * Expects that an error occurred during command execution.
     *
     * This method validates that the command execution resulted in an exception
     * or error condition, without specifying the error type.
     *
     * @return the current expecter instance for method chaining
     * @throws AssertionError if no error occurred during command execution
     */
    fun expectError(): AE = expect {
        error.assert().describedAs { "Expect an error." }.isNotNull()
    }

    /**
     * Expects a specific error with detailed validation.
     *
     * This method validates both that an error occurred and that it matches
     * the expected error type and properties.
     *
     * @param E the expected type of the Throwable
     * @param expected a lambda function that receives the error and performs detailed assertions
     * @return the current expecter instance for method chaining
     * @throws AssertionError if no error occurred or the error doesn't match expectations
     * @throws ClassCastException if the error cannot be cast to the expected type
     */
    fun <E : Throwable> expectError(expected: E.() -> Unit): AE = expectError().expect {
        @Suppress("UNCHECKED_CAST")
        expected(error as E)
    }

    /**
     * Expects a specific error with detailed validation using a Consumer.
     *
     * Java-friendly overload for expectError that accepts a Consumer instead of a lambda.
     *
     * @param E the expected type of the Throwable
     * @param expected a Consumer that receives the error and performs detailed assertions
     * @return the current expecter instance for method chaining
     */
    fun <E : Throwable> expectError(expected: Consumer<E>): AE = expectError<E> {
        expected.accept(this)
    }

    /**
     * Expects an error of a specific type.
     *
     * This method validates that an error occurred and that it is an instance
     * of the expected Throwable type.
     *
     * @param E the expected type of the Throwable
     * @param expected the KClass representing the expected error type
     * @return the current expecter instance for method chaining
     * @throws AssertionError if no error occurred or the error is not of the expected type
     */
    fun <E : Throwable> expectErrorType(expected: KClass<E>): AE = expectError<E> {
        assert().isInstanceOf(expected.java)
    }

    /**
     * Expects an error of a specific type (Java-friendly overload).
     *
     * This method accepts a Class object instead of KClass, making it more convenient
     * for Java integration while maintaining the same validation logic.
     *
     * @param E the expected type of the Throwable
     * @param expected the Class representing the expected error type
     * @return the current expecter instance for method chaining
     * @throws AssertionError if no error occurred or the error is not of the expected type
     */
    fun <E : Throwable> expectErrorType(expected: Class<E>): AE = expectErrorType(expected.kotlin)
}
