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

interface AggregateExpecter<S : Any, AE : AggregateExpecter<S, AE>> {
    fun expect(expected: ExpectedResult<S>.() -> Unit): AE

    /**
     * 3.1 期望聚合状态.
     */
    fun expectStateAggregate(expected: StateAggregate<S>.() -> Unit): AE {
        return expect { expected(stateAggregate) }
    }

    fun expectState(expected: S.() -> Unit): AE {
        return expectStateAggregate { expected(state) }
    }

    fun expectState(expected: Consumer<S>): AE {
        return expectState { expected.accept(this) }
    }

    /**
     * 3.2 期望领域事件.
     */
    fun expectEventStream(expected: DomainEventStream.() -> Unit): AE {
        return expect {
            domainEventStream.assert().describedAs {
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
    }

    fun expectEventStream(expected: Consumer<DomainEventStream>): AE {
        return expectEventStream {
            expected.accept(this)
        }
    }

    fun expectEventIterator(expected: EventIterator.() -> Unit): AE {
        return expectEventStream {
            expected(EventIterator((iterator())))
        }
    }

    fun expectEventIterator(expected: Consumer<EventIterator>): AE {
        return expectEventIterator {
            expected.accept(this)
        }
    }

    /**
     * 期望的第一个领域事件
     */
    fun <E : Any> expectEvent(expected: DomainEvent<E>.() -> Unit): AE {
        return expectEventStream {
            assert().describedAs { "Expect the domain event stream size to be greater than 1." }
                .hasSizeGreaterThanOrEqualTo(1)
            @Suppress("UNCHECKED_CAST")
            expected(first() as DomainEvent<E>)
        }
    }

    fun <E : Any> expectEvent(expected: Consumer<DomainEvent<E>>): AE {
        return expectEvent {
            expected.accept(this)
        }
    }

    fun <E : Any> expectEventBody(expected: E.() -> Unit): AE {
        return expectEvent {
            expected(body)
        }
    }

    fun <E : Any> expectEventBody(expected: Consumer<E>): AE {
        return expectEventBody<E> {
            expected.accept(this)
        }
    }

    /**
     * 期望产生的事件数量.
     */
    fun expectEventCount(expected: Int): AE {
        return expectEventStream {
            assert().describedAs { "Expect the domain event stream size." }.hasSize(expected)
        }
    }

    fun expectEventType(vararg expected: KClass<*>): AE {
        return expectEventCount(expected.size).expectEventStream {
            val itr = iterator()
            for (eventType in expected) {
                itr.next().body.assert().isInstanceOf(eventType.java)
            }
        }
    }

    /**
     * 期望事件类型. 严格按照事件生成顺序验证。
     */
    fun expectEventType(vararg expected: Class<*>): AE {
        return expectEventType(*expected.map { it.kotlin }.toTypedArray())
    }

    fun expectNoError(): AE {
        return expect {
            error.assert().describedAs { "Expect no error" }.isNull()
        }
    }

    fun expectError(): AE {
        return expect {
            error.assert().describedAs { "Expect an error." }.isNotNull()
        }
    }

    /**
     * 3.3 期望产生异常
     */
    fun <E : Throwable> expectError(expected: E.() -> Unit): AE {
        return expectError().expect {
            @Suppress("UNCHECKED_CAST")
            expected(error as E)
        }
    }

    fun <E : Throwable> expectError(expected: Consumer<E>): AE {
        return expectError<E> {
            expected.accept(this)
        }
    }

    fun <E : Throwable> expectErrorType(expected: KClass<E>): AE {
        return expectError<E> {
            assert().isInstanceOf(expected.java)
        }
    }

    fun <E : Throwable> expectErrorType(expected: Class<E>): AE {
        return expectErrorType(expected.kotlin)
    }
}
