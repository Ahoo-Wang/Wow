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

    fun <C : Any> nextCommand(commandType: KClass<C>): CommandMessage<C> {
        return nextCommand(commandType.java)
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

    fun <C : Any> nextCommandBody(commandType: KClass<C>): C {
        return nextCommand(commandType).body
    }

    fun <C : Any> nextCommandBody(commandType: Class<C>): C {
        return nextCommand(commandType).body
    }

    inline fun <reified C : Any> nextCommand(): CommandMessage<C> {
        return nextCommand(C::class)
    }

    inline fun <reified C : Any> nextCommandBody(): C {
        return nextCommandBody(C::class)
    }
}
