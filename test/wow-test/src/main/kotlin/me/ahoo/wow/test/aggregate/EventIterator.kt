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

class EventIterator(override val delegate: Iterator<DomainEvent<*>>) :
    Iterator<DomainEvent<*>> by delegate,
    Decorator<Iterator<DomainEvent<*>>> {

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

    fun <E : Any> nextEvent(eventType: KClass<E>): DomainEvent<E> {
        return nextEvent(eventType.java)
    }

    @Suppress("UNCHECKED_CAST")
    fun <E : Any> nextEvent(eventType: Class<E>): DomainEvent<E> {
        hasNext().assert().describedAs { "Expect there to be a next event." }.isTrue()
        val nextEvent = next()
        nextEvent.body.assert()
            .describedAs { "Expect the next event body to be an instance of ${eventType.simpleName}." }
            .isInstanceOf(eventType)
        return nextEvent as DomainEvent<E>
    }

    fun <E : Any> nextEventBody(eventType: KClass<E>): E {
        return nextEvent(eventType).body
    }

    fun <E : Any> nextEventBody(eventType: Class<E>): E {
        return nextEvent(eventType).body
    }

    inline fun <reified E : Any> nextEvent(): DomainEvent<E> {
        return nextEvent(E::class)
    }

    inline fun <reified E : Any> nextEventBody(): E {
        return nextEventBody(E::class)
    }
}
