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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.saga.stateless.CommandStream
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue

/**
 * Stateless Saga:
 * 1. when event
 * 2. expect commands
 */
interface WhenStage<T : Any> {
    fun <SERVICE : Any> inject(service: SERVICE): WhenStage<T>

    /**
     * 1. 当订阅到领域事件时，生成聚合命令.
     */
    fun `when`(event: Any): ExpectStage<T>
}

interface ExpectStage<T : Any> {
    fun expect(expected: (ExpectedResult<T>) -> Unit): ExpectStage<T>

    fun expectCommandStream(expected: (CommandStream) -> Unit): ExpectStage<T> {
        return expectNoError().expect {
            assertThat(it.commandStream, notNullValue())
            checkNotNull(it.commandStream)
            expected(it.commandStream)
        }
    }

    fun expectNoCommand(): ExpectStage<T> {
        return expectNoError().expect {
            assertThat(it.commandStream, nullValue())
        }
    }

    /**
     * 期望的第一个命令
     */
    fun <C : Any> expectCommand(expected: (CommandMessage<C>) -> Unit): ExpectStage<T> {
        return expectNoError().expect {
            assertThat(it.commandStream, notNullValue())
            checkNotNull(it.commandStream)
            assertThat(it.commandStream.size, greaterThanOrEqualTo(1))
            @Suppress("UNCHECKED_CAST")
            expected(it.commandStream.first() as CommandMessage<C>)
        }
    }

    fun <C : Any> expectCommandBody(expected: (C) -> Unit): ExpectStage<T> {
        return expectCommand {
            expected(it.body)
        }
    }

    fun expectCommandCount(expected: Int): ExpectStage<T> {
        return expectNoError().expect {
            assertThat(it.commandStream, notNullValue())
            checkNotNull(it.commandStream)
            assertThat(it.commandStream.size, equalTo(expected))
        }
    }

    fun expectCommandType(vararg expected: Class<*>): ExpectStage<T> {
        return expectCommandCount(expected.size).expectCommandStream {
            val itr = it.iterator()
            for (eventType in expected) {
                assertThat(itr.next().body, instanceOf(eventType))
            }
        }
    }

    fun expectNoError(): ExpectStage<T> {
        return expect {
            assertThat(it.error, nullValue())
        }
    }

    fun expectError(): ExpectStage<T> {
        return expect {
            assertThat(it.error, notNullValue())
        }
    }

    fun expectError(expected: (Throwable) -> Unit): ExpectStage<T> {
        return expectError().expect {
            expected(it.error!!)
        }
    }

    fun expectErrorType(expected: Class<out Throwable>): ExpectStage<T> {
        return expectError { assertThat(it, instanceOf(expected)) }
    }

    /**
     * 完成流程编排后，执行验证逻辑.
     */
    fun verify()
}

data class ExpectedResult<T>(
    val processor: T,
    val commandStream: CommandStream?,
    val error: Throwable? = null,
) {
    val hasError = error != null
}
