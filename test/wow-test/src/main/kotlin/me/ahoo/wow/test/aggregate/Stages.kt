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

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.naming.annotation.toName
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import java.util.function.Consumer

interface GivenStage<S : Any> {
    fun <SERVICE : Any> inject(service: SERVICE, serviceName: String): GivenStage<S>
    fun <SERVICE : Any> inject(service: SERVICE): GivenStage<S> {
        return inject(service = service, serviceName = service.javaClass.toName())
    }

    /**
     * 1. 给定领域事件，朔源聚合.
     */
    fun given(vararg events: Any): WhenStage<S>
    fun givenEvent(vararg events: Any): WhenStage<S> {
        return given(*events)
    }
}

interface WhenStage<S : Any> {
    /**
     * 2. 接收并执行命令.
     */
    fun `when`(command: Any, header: Header): ExpectStage<S>

    fun `when`(command: Any): ExpectStage<S> {
        return this.`when`(command = command, header = DefaultHeader.empty())
    }

    fun whenCommand(command: Any, header: Header = DefaultHeader.empty()): ExpectStage<S> {
        return this.`when`(command = command, header = header)
    }
}

interface ExpectStage<S : Any> {
    fun expect(expected: Consumer<ExpectedResult<S>>): ExpectStage<S>

    /**
     * 3.1 期望聚合状态.
     */
    fun expectStateAggregate(expected: Consumer<StateAggregate<S>>): ExpectStage<S> {
        return expect { expected.accept(it.stateAggregate) }
    }

    fun expectState(expected: Consumer<S>): ExpectStage<S> {
        return expectStateAggregate { expected.accept(it.state) }
    }

    /**
     * 3.2 期望领域事件.
     */
    fun expectEventStream(expected: Consumer<DomainEventStream>): ExpectStage<S> {
        return expect {
            assertThat("Expect the domain event stream is not null.", it.domainEventStream, notNullValue())
            expected.accept(it.domainEventStream!!)
        }
    }

    fun expectEventIterator(expected: Consumer<EventIterator>): ExpectStage<S> {
        return expectEventStream {
            expected.accept(EventIterator((it.iterator())))
        }
    }

    /**
     * 期望的第一个领域事件
     */
    fun <E : Any> expectEvent(expected: Consumer<DomainEvent<E>>): ExpectStage<S> {
        return expectEventStream {
            assertThat("Expect the domain event stream size to be greater than 1.", it.size, greaterThanOrEqualTo(1))
            @Suppress("UNCHECKED_CAST")
            expected.accept(it.first() as DomainEvent<E>)
        }
    }

    fun <E : Any> expectEventBody(expected: Consumer<E>): ExpectStage<S> {
        return expectEvent {
            expected.accept(it.body)
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
    fun <E : Throwable> expectError(expected: Consumer<E>): ExpectStage<S> {
        return expectError().expect {
            @Suppress("UNCHECKED_CAST")
            expected.accept(it.error as E)
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

    fun then(verifyError: Boolean = true): GivenStage<S>

    /**
     * 为当前环境创建一个完全独立的测试分支上下文
     */
    fun fork(verifyError: Boolean = true, handle: GivenStage<S>.(ExpectedResult<S>) -> Unit): VerifiedStage<S>
}

data class ExpectedResult<S : Any>(
    val exchange: ServerCommandExchange<*>,
    val stateAggregate: StateAggregate<S>,
    val domainEventStream: DomainEventStream? = null,
    val error: Throwable? = null
) {
    val hasError = error != null
}

class EventIterator(override val delegate: Iterator<DomainEvent<*>>) :
    Iterator<DomainEvent<*>> by delegate,
    Decorator<Iterator<DomainEvent<*>>> {

    @Suppress("UNCHECKED_CAST")
    fun <E : Any> nextEvent(eventType: Class<E>): DomainEvent<E> {
        assertThat("Expect the next command.", hasNext(), equalTo(true))
        val nextEvent = next()
        assertThat("Expect the event body type.", nextEvent.body, instanceOf(eventType))
        return nextEvent as DomainEvent<E>
    }

    fun <E : Any> nextEventBody(eventType: Class<E>): E {
        return nextEvent(eventType).body
    }

    inline fun <reified E : Any> nextEvent(): DomainEvent<E> {
        return nextEvent(E::class.java)
    }

    inline fun <reified E : Any> nextEventBody(): E {
        return nextEventBody(E::class.java)
    }
}

fun <S : Any> GivenStage<S>.`when`(command: Any, header: Header = DefaultHeader.empty()): ExpectStage<S> {
    return this.givenEvent().`when`(command, header)
}

fun <S : Any> GivenStage<S>.whenCommand(command: Any, header: Header = DefaultHeader.empty()): ExpectStage<S> {
    return this.`when`(command, header)
}
