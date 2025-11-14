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
import me.ahoo.wow.infra.Decorator
import kotlin.reflect.KClass

/**
 * A decorator for iterating over domain events with convenient access methods.
 *
 * This class provides type-safe methods for traversing domain event streams,
 * allowing easy access to event bodies and skipping events during testing.
 *
 * Example usage:
 * ```kotlin
 * val eventIterator = EventIterator(domainEventStream.iterator())
 *
 * // Skip the first event
 * eventIterator.skip(1)
 *
 * // Get the next event as a specific type
 * val orderCreated = eventIterator.nextEventBody<OrderCreated>()
 *
 * // Get the full event with metadata
 * val paymentEvent = eventIterator.nextEvent<OrderPaid>()
 * ```
 *
 * @param delegate the underlying iterator to decorate
 */
class EventIterator(
    override val delegate: Iterator<DomainEvent<*>>
) : Iterator<DomainEvent<*>> by delegate,
    Decorator<Iterator<DomainEvent<*>>> {
    /**
     * Skips a specified number of events in the iterator.
     *
     * This method advances the iterator by the given number of events,
     * allowing tests to focus on events after a certain point.
     *
     * @param skip the number of events to skip (must be non-negative)
     * @return this EventIterator for method chaining
     * @throws IllegalArgumentException if skip is negative
     * @throws AssertionError if there are not enough events to skip
     */
    fun skip(skip: Int): EventIterator {
        require(skip >= 0) { "Skip value must be non-negative, but was: $skip" }
        repeat(skip) {
            hasNext().assert()
                .describedAs { "Not enough events to skip $skip times. Current skip times: $it" }
                .isTrue()
            next()
        }
        return this
    }

    /**
     * Retrieves the next event and validates its type using KClass.
     *
     * This method advances the iterator and ensures the next event's body
     * is of the expected type.
     *
     * @param E the expected type of the event body
     * @param eventType the KClass representing the expected event body type
     * @return the next DomainEvent with the validated type
     * @throws AssertionError if there is no next event or the event type doesn't match
     */
    fun <E : Any> nextEvent(eventType: KClass<E>): DomainEvent<E> = nextEvent(eventType.java)

    /**
     * Retrieves the next event and validates its type using Class.
     *
     * This method advances the iterator and ensures the next event's body
     * is of the expected type. Java-friendly overload using Class instead of KClass.
     *
     * @param E the expected type of the event body
     * @param eventType the Class representing the expected event body type
     * @return the next DomainEvent with the validated type
     * @throws AssertionError if there is no next event or the event type doesn't match
     */
    @Suppress("UNCHECKED_CAST")
    fun <E : Any> nextEvent(eventType: Class<E>): DomainEvent<E> {
        hasNext().assert().describedAs { "Expect there to be a next event." }.isTrue()
        val nextEvent = next()
        nextEvent.body
            .assert()
            .describedAs { "Expect the next event body to be an instance of ${eventType.simpleName}." }
            .isInstanceOf(eventType)
        return nextEvent as DomainEvent<E>
    }

    /**
     * Retrieves the body of the next event and validates its type using KClass.
     *
     * This method advances the iterator and returns only the event body,
     * ensuring it is of the expected type.
     *
     * @param E the expected type of the event body
     * @param eventType the KClass representing the expected event body type
     * @return the body of the next event with the validated type
     * @throws AssertionError if there is no next event or the event type doesn't match
     */
    fun <E : Any> nextEventBody(eventType: KClass<E>): E = nextEvent(eventType).body

    /**
     * Retrieves the body of the next event and validates its type using Class.
     *
     * This method advances the iterator and returns only the event body,
     * ensuring it is of the expected type. Java-friendly overload using Class instead of KClass.
     *
     * @param E the expected type of the event body
     * @param eventType the Class representing the expected event body type
     * @return the body of the next event with the validated type
     * @throws AssertionError if there is no next event or the event type doesn't match
     */
    fun <E : Any> nextEventBody(eventType: Class<E>): E = nextEvent(eventType).body

    /**
     * Retrieves the next event with reified type validation.
     *
     * This inline method uses reified generics to automatically infer the event type,
     * providing a convenient way to get typed events without explicit type parameters.
     *
     * @param E the expected type of the event body (inferred by reification)
     * @return the next DomainEvent with the validated type
     * @throws AssertionError if there is no next event or the event type doesn't match
     */
    inline fun <reified E : Any> nextEvent(): DomainEvent<E> = nextEvent(E::class)

    /**
     * Retrieves the body of the next event with reified type validation.
     *
     * This inline method uses reified generics to automatically infer the event type,
     * providing a convenient way to get typed event bodies without explicit type parameters.
     *
     * @param E the expected type of the event body (inferred by reification)
     * @return the body of the next event with the validated type
     * @throws AssertionError if there is no next event or the event type doesn't match
     */
    inline fun <reified E : Any> nextEventBody(): E = nextEventBody(E::class)
}
