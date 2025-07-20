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
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.function.Consumer
import kotlin.reflect.KClass

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
            it.domainEventStream.assert().describedAs {
                buildString {
                    append("Expect the domain event stream is not null.")
                    it.error?.let { error ->
                        appendLine()
                        append(error.stackTraceToString())
                    }
                }
            }.isNotNull()
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
            it.assert().describedAs { "Expect the domain event stream size to be greater than 1." }
                .hasSizeGreaterThanOrEqualTo(1)
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
            it.assert().describedAs { "Expect the domain event stream size." }.hasSize(expected)
        }
    }

    fun expectEventType(vararg expected: KClass<*>): ExpectStage<S> {
        return expectEventType(*expected.map { it.java }.toTypedArray())
    }

    /**
     * 期望事件类型. 严格按照事件生成顺序验证。
     */
    fun expectEventType(vararg expected: Class<*>): ExpectStage<S> {
        return expectEventCount(expected.size).expectEventStream {
            val itr = it.iterator()
            for (eventType in expected) {
                itr.next().body.assert().isInstanceOf(eventType)
            }
        }
    }

    fun expectNoError(): ExpectStage<S> {
        return expect {
            it.error.assert().describedAs { "Expect no error" }.isNull()
        }
    }

    fun expectError(): ExpectStage<S> {
        return expect {
            it.error.assert().describedAs { "Expect an error." }.isNotNull()
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

    fun <E : Throwable> expectErrorType(expected: KClass<E>): ExpectStage<S> {
        return expectErrorType(expected.java)
    }

    fun <E : Throwable> expectErrorType(expected: Class<E>): ExpectStage<S> {
        return expectError<E> {
            it.assert().isInstanceOf(expected)
        }
    }

    /**
     * 完成流程编排后，执行验证逻辑.
     */
    fun verify(): VerifiedStage<S>
}

internal class DefaultExpectStage<C : Any, S : Any>(
    private val metadata: AggregateMetadata<C, S>,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val serviceProvider: ServiceProvider,
    private val expectedResultMono: Mono<ExpectedResult<S>>
) : ExpectStage<S> {

    private val expectStates: MutableList<Consumer<ExpectedResult<S>>> = mutableListOf()

    @Volatile
    private var cachedVerifiedStage: VerifiedStage<S>? = null
    override fun expect(expected: Consumer<ExpectedResult<S>>): ExpectStage<S> {
        expectStates.add(expected)
        return this
    }

    override fun verify(): VerifiedStage<S> {
        if (cachedVerifiedStage != null) {
            return cachedVerifiedStage!!
        }
        lateinit var expectedResult: ExpectedResult<S>
        expectedResultMono
            .test()
            .consumeNextWith {
                verifyStateAggregateSerializable(it.stateAggregate)
                expectedResult = it
                for (expectState in expectStates) {
                    expectState.accept(it)
                }
            }
            .verifyComplete()
        cachedVerifiedStage = DefaultVerifiedStage(
            verifiedResult = expectedResult,
            metadata = metadata,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
        )
        return cachedVerifiedStage!!
    }
}
