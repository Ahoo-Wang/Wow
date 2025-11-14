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
import me.ahoo.wow.infra.Decorator
import kotlin.reflect.KClass

/**
 * A decorator iterator for command messages that provides testing utilities.
 *
 * This class wraps an iterator of [CommandMessage] instances and provides
 * convenient methods for skipping commands and asserting command types
 * during saga testing.
 *
 * @property delegate The underlying iterator being decorated.
 */
class CommandIterator(
    override val delegate: Iterator<CommandMessage<*>>
) : Iterator<CommandMessage<*>> by delegate,
    Decorator<Iterator<CommandMessage<*>>> {
    /**
     * Skips a specified number of commands in the iterator.
     *
     * This method advances the iterator by the given number of commands,
     * ensuring that enough commands are available to skip.
     *
     * @param skip The number of commands to skip (must be non-negative).
     * @return This iterator instance for method chaining.
     * @throws IllegalArgumentException If skip is negative.
     * @throws AssertionError If there are not enough commands to skip.
     */
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

    /**
     * Retrieves the next command and asserts it is of the specified type.
     *
     * @param C The expected type of the command body.
     * @param commandType The [KClass] of the expected command body type.
     * @return The next command message cast to the expected type.
     * @throws AssertionError If no next command exists or the command type doesn't match.
     */
    fun <C : Any> nextCommand(commandType: KClass<C>): CommandMessage<C> = nextCommand(commandType.java)

    /**
     * Retrieves the next command and asserts it is of the specified type (Java Class version).
     *
     * This is a convenience overload that accepts a Java [Class] instance.
     *
     * @param C The expected type of the command body.
     * @param commandType The [Class] of the expected command body type.
     * @return The next command message cast to the expected type.
     * @throws AssertionError If no next command exists or the command type doesn't match.
     */
    @Suppress("UNCHECKED_CAST")
    fun <C : Any> nextCommand(commandType: Class<C>): CommandMessage<C> {
        hasNext().assert().describedAs { "Expect there to be a next command." }.isTrue()
        val nextCommand = next()
        nextCommand.body
            .assert()
            .describedAs { "Expect the next command body to be an instance of ${commandType.simpleName}." }
            .isInstanceOf(commandType)
        return nextCommand as CommandMessage<C>
    }

    /**
     * Retrieves the body of the next command and asserts it is of the specified type.
     *
     * @param C The expected type of the command body.
     * @param commandType The [KClass] of the expected command body type.
     * @return The body of the next command cast to the expected type.
     * @throws AssertionError If no next command exists or the command type doesn't match.
     */
    fun <C : Any> nextCommandBody(commandType: KClass<C>): C = nextCommand(commandType).body

    /**
     * Retrieves the body of the next command and asserts it is of the specified type (Java Class version).
     *
     * This is a convenience overload that accepts a Java [Class] instance.
     *
     * @param C The expected type of the command body.
     * @param commandType The [Class] of the expected command body type.
     * @return The body of the next command cast to the expected type.
     * @throws AssertionError If no next command exists or the command type doesn't match.
     */
    fun <C : Any> nextCommandBody(commandType: Class<C>): C = nextCommand(commandType).body

    /**
     * Retrieves the next command with reified type checking.
     *
     * This inline function uses reified generics to automatically infer the command type.
     *
     * @param C The expected type of the command body (inferred).
     * @return The next command message cast to the expected type.
     * @throws AssertionError If no next command exists or the command type doesn't match.
     */
    inline fun <reified C : Any> nextCommand(): CommandMessage<C> = nextCommand(C::class)

    /**
     * Retrieves the body of the next command with reified type checking.
     *
     * This inline function uses reified generics to automatically infer the command type.
     *
     * @param C The expected type of the command body (inferred).
     * @return The body of the next command cast to the expected type.
     * @throws AssertionError If no next command exists or the command type doesn't match.
     */
    inline fun <reified C : Any> nextCommandBody(): C = nextCommandBody(C::class)
}
