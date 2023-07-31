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
import me.ahoo.wow.event.DomainEvent
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.naming.annotation.asName
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*

interface GivenStage<S : Any> {
    fun <SERVICE : Any> inject(service: SERVICE, serviceName: String = service.javaClass.asName()): GivenStage<S>

    /**
     * 1. 给定领域事件，朔源聚合.
     */
    fun given(vararg events: Any): WhenStage<S>
}

interface WhenStage<S : Any> {
    /**
     * 2. 接收并执行命令.
     */
    fun `when`(command: Any, header: Header = DefaultHeader.empty()): ExpectStage<S>
}

interface ExpectStage<S : Any> {
    fun expect(expected: (ExpectedResult<S>) -> Unit): ExpectStage<S>

    /**
     * 3.1 期望聚合状态.
     */
    fun expectStateAggregate(expected: (StateAggregate<S>) -> Unit): ExpectStage<S> {
        return expect { expected(it.stateAggregate) }
    }

    fun expectState(expected: (S) -> Unit): ExpectStage<S> {
        return expectStateAggregate { expected(it.state) }
    }

    /**
     * 3.2 期望领域事件.
     */
    fun expectEventStream(expected: (DomainEventStream) -> Unit): ExpectStage<S> {
        return expect {
            assertThat("Expect the domain event stream is not null.", it.domainEventStream, notNullValue())
            expected(it.domainEventStream!!)
        }
    }

    /**
     * 期望的第一个领域事件
     */
    fun <E : Any> expectEvent(expected: (DomainEvent<E>) -> Unit): ExpectStage<S> {
        return expectEventStream {
            assertThat("Expect the domain event stream size to be greater than 1.", it.size, greaterThanOrEqualTo(1))
            @Suppress("UNCHECKED_CAST")
            expected(it.first() as DomainEvent<E>)
        }
    }

    fun <E : Any> expectEventBody(expected: (E) -> Unit): ExpectStage<S> {
        return expectEvent {
            expected(it.body)
        }
    }

    /**
     * 期望产生的事件数量.
     */
    fun expectEventCount(expected: Int): ExpectStage<S> {
        return expectEventStream {
            assertThat("Expect the domain event stream size.", it.size, equalTo(expected))
        }
    }

    /**
     * 期望事件类型. 严格按照事件生成顺序验证。
     */
    fun expectEventType(vararg expected: Class<*>): ExpectStage<S> {
        return expectEventCount(expected.size).expectEventStream {
            val itr = it.iterator()
            for (eventType in expected) {
                assertThat(itr.next().body, instanceOf(eventType))
            }
        }
    }

    fun expectNoError(): ExpectStage<S> {
        return expect {
            assertThat("Expect no error", it.error, nullValue())
        }
    }

    fun expectError(): ExpectStage<S> {
        return expect {
            assertThat("Expect an error.", it.error, notNullValue())
        }
    }

    /**
     * 3.3 期望产生异常
     */
    fun <E : Throwable> expectError(expected: (E) -> Unit): ExpectStage<S> {
        return expectError().expect {
            @Suppress("UNCHECKED_CAST")
            expected(it.error as E)
        }
    }

    fun <E : Throwable> expectErrorType(expected: Class<E>): ExpectStage<S> {
        return expectError<E> { assertThat(it, instanceOf(expected)) }
    }

    /**
     * 完成流程编排后，执行验证逻辑.
     */
    fun verify(): VerifiedStage<S>
}

interface VerifiedStage<S : Any> {
    val verifiedResult: ExpectedResult<S>
    val stateAggregate: StateAggregate<S>
        get() = verifiedResult.stateAggregate
    val stateRoot: S
        get() = stateAggregate.state

    fun then(): GivenStage<S>
}

data class ExpectedResult<S : Any>(
    val stateAggregate: StateAggregate<S>,
    val domainEventStream: DomainEventStream? = null,
    val error: Throwable? = null
) {
    val hasError = error != null

    private val eventStreamItr by lazy {
        checkNotNull(domainEventStream)
        domainEventStream.iterator()
    }

    fun hasNextEvent(): Boolean {
        return eventStreamItr.hasNext()
    }

    @Suppress("UNCHECKED_CAST")
    fun <E : Any> nextEvent(): DomainEvent<E> {
        assertThat("Expect the next domain event.", hasNextEvent(), equalTo(true))
        return eventStreamItr.next() as DomainEvent<E>
    }

    fun <C : Any> nextEventBody(): C {
        return nextEvent<C>().body
    }
}
