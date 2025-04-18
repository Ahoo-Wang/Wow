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

package me.ahoo.wow.test.saga.stateless

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.naming.annotation.toName
import me.ahoo.wow.saga.stateless.CommandStream
import java.util.function.Consumer

/**
 * Stateless Saga:
 * 1. when event
 * 2. expect commands
 */
interface WhenStage<T : Any> {
    fun <SERVICE : Any> inject(service: SERVICE, serviceName: String): WhenStage<T>

    fun <SERVICE : Any> inject(service: SERVICE): WhenStage<T> {
        return inject(service, service.javaClass.toName())
    }

    fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean): WhenStage<T>

    fun functionName(functionName: String): WhenStage<T> {
        return functionFilter {
            it.name == functionName
        }
    }

    /**
     * 1. 当订阅到领域事件时，生成聚合命令.
     */
    fun `when`(event: Any, state: Any?, ownerId: String = OwnerId.DEFAULT_OWNER_ID): ExpectStage<T> {
        return whenEvent(event = event, state = state, ownerId = ownerId)
    }

    fun `when`(event: Any): ExpectStage<T> {
        return whenEvent(event = event, state = null)
    }

    fun whenEvent(event: Any, state: Any? = null, ownerId: String = OwnerId.DEFAULT_OWNER_ID): ExpectStage<T>
}

interface ExpectStage<T : Any> {
    fun expect(expected: Consumer<ExpectedResult<T>>): ExpectStage<T>

    fun expectCommandStream(expected: Consumer<CommandStream>): ExpectStage<T> {
        return expectNoError().expect {
            it.commandStream.assert().describedAs { "Expect the command stream is not null." }.isNotNull()
            expected.accept(it.commandStream!!)
        }
    }

    fun expectCommandIterator(expected: Consumer<CommandIterator>): ExpectStage<T> {
        return expectCommandStream {
            expected.accept(CommandIterator(it.iterator()))
        }
    }

    /**
     * expectCommandCount(0)
     * @see expectCommandCount
     */
    fun expectNoCommand(): ExpectStage<T> {
        return expectCommandCount(0)
    }

    /**
     * 期望的第一个命令
     */
    fun <C : Any> expectCommand(expected: Consumer<CommandMessage<C>>): ExpectStage<T> {
        return expectCommandStream {
            it.assert()
                .describedAs { "Expect the command stream size to be greater than 1." }
                .hasSizeGreaterThanOrEqualTo(1)
            @Suppress("UNCHECKED_CAST")
            expected.accept(it.first() as CommandMessage<C>)
        }
    }

    fun <C : Any> expectCommandBody(expected: Consumer<C>): ExpectStage<T> {
        return expectCommand {
            expected.accept(it.body)
        }
    }

    fun expectCommandCount(expected: Int): ExpectStage<T> {
        return expectCommandStream {
            it.assert().describedAs { "Expect the command stream size." }.hasSize(expected)
        }
    }

    fun expectCommandType(vararg expected: Class<*>): ExpectStage<T> {
        return expectCommandCount(expected.size).expectCommandStream {
            val itr = it.iterator()
            for (eventType in expected) {
                itr.next().body.assert().isInstanceOf(eventType)
            }
        }
    }

    fun expectNoError(): ExpectStage<T> {
        return expect {
            it.error.assert().describedAs { "Expect no error." }.isNull()
        }
    }

    fun expectError(): ExpectStage<T> {
        return expect {
            it.error.assert().describedAs { "Expect an error." }.isNotNull()
        }
    }

    fun <E : Throwable> expectError(expected: Consumer<E>): ExpectStage<T> {
        return expectError().expect {
            @Suppress("UNCHECKED_CAST")
            expected.accept(it.error as E)
        }
    }

    fun <E : Throwable> expectErrorType(expected: Class<E>): ExpectStage<T> {
        return expectError<E> {
            it.assert().isInstanceOf(expected)
        }
    }

    /**
     * 完成流程编排后，执行验证逻辑.
     */
    fun verify()
}

data class ExpectedResult<T>(
    val exchange: DomainEventExchange<*>,
    val processor: T,
    val commandStream: CommandStream?,
    val error: Throwable? = null
) {
    val hasError = error != null
}

class CommandIterator(override val delegate: Iterator<CommandMessage<*>>) :
    Iterator<CommandMessage<*>> by delegate,
    Decorator<Iterator<CommandMessage<*>>> {

    fun skip(skip: Int): CommandIterator {
        require(skip >= 0) { "Skip value must be non-negative, but was: $skip" }
        repeat(skip) {
            hasNext().assert()
                .describedAs { "Not enough commands to skip $skip times. Current skip times: $it" }
                .isTrue()
            next()
        }
        return this
    }

    @Suppress("UNCHECKED_CAST")
    fun <C : Any> nextCommand(commandType: Class<C>): CommandMessage<C> {
        hasNext().assert().describedAs { "Expect there to be a next command." }.isTrue()
        val nextCommand = next()
        nextCommand.body.assert()
            .describedAs { "Expect the next command body to be an instance of ${commandType.simpleName}." }
            .isInstanceOf(commandType)
        return nextCommand as CommandMessage<C>
    }

    fun <C : Any> nextCommandBody(commandType: Class<C>): C {
        return nextCommand(commandType).body
    }

    inline fun <reified C : Any> nextCommand(): CommandMessage<C> {
        return nextCommand(C::class.java)
    }

    inline fun <reified C : Any> nextCommandBody(): C {
        return nextCommandBody(C::class.java)
    }
}
