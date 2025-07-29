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
import me.ahoo.wow.saga.stateless.CommandStream
import kotlin.reflect.KClass

interface StatelessSagaExpecter<T : Any, SE : StatelessSagaExpecter<T, SE>> {
    fun expect(expected: ExpectedResult<T>.() -> Unit): SE

    fun expectCommandStream(expected: CommandStream.() -> Unit): SE {
        return expectNoError().expect {
            commandStream.assert().describedAs { "Expect the command stream is not null." }.isNotNull()
            expected(commandStream!!)
        }
    }

    fun expectCommandIterator(expected: CommandIterator.() -> Unit): SE {
        return expectCommandStream {
            expected(CommandIterator(iterator()))
        }
    }

    /**
     * expectCommandCount(0)
     * @see expectCommandCount
     */
    fun expectNoCommand(): SE {
        return expectCommandCount(0)
    }

    /**
     * 期望的第一个命令
     */
    fun <C : Any> expectCommand(expected: CommandMessage<C>.() -> Unit): SE {
        return expectCommandStream {
            assert()
                .describedAs { "Expect the command stream size to be greater than 1." }
                .hasSizeGreaterThanOrEqualTo(1)
            @Suppress("UNCHECKED_CAST")
            expected(first() as CommandMessage<C>)
        }
    }

    fun <C : Any> expectCommandBody(expected: C.() -> Unit): SE {
        return expectCommand {
            expected(body)
        }
    }

    fun expectCommandCount(expected: Int): SE {
        return expectCommandStream {
            assert().describedAs { "Expect the command stream size." }.hasSize(expected)
        }
    }

    fun expectCommandType(vararg expected: KClass<*>): SE {
        return expectCommandCount(expected.size).expectCommandStream {
            val itr = iterator()
            for (eventType in expected) {
                itr.next().body.assert().isInstanceOf(eventType.java)
            }
        }
    }

    fun expectCommandType(vararg expected: Class<*>): SE {
        return expectCommandType(*expected.map { it.kotlin }.toTypedArray())
    }

    fun expectNoError(): SE {
        return expect {
            error.assert().describedAs { "Expect no error." }.isNull()
        }
    }

    fun expectError(): SE {
        return expect {
            error.assert().describedAs { "Expect an error." }.isNotNull()
        }
    }

    fun <E : Throwable> expectError(expected: E.() -> Unit): SE {
        return expectError().expect {
            @Suppress("UNCHECKED_CAST")
            expected(error as E)
        }
    }

    fun <E : Throwable> expectErrorType(expected: KClass<E>): SE {
        return expectError<E> {
            assert().isInstanceOf(expected.java)
        }
    }

    fun <E : Throwable> expectErrorType(expected: Class<E>): SE {
        return expectErrorType(expected.kotlin)
    }
}
