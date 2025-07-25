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
package me.ahoo.wow.modeling.annotation

import me.ahoo.wow.api.annotation.AfterCommand
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.command.ServerCommandExchange

class MockAggregate(val id: String)

class MockCommandAggregateWithoutConstructor

class MockAggregateWithAggregateId(@AggregateId val id: String)

abstract class MockAbstractStateAggregate(val id: String)

abstract class MockAbstractCommandAggregate<S : MockAbstractStateAggregate>(val state: S)

class MockStateAggregate(id: String) : MockAbstractStateAggregate(id)
class MockCommandAggregate(state: MockStateAggregate) : MockAbstractCommandAggregate<MockStateAggregate>(state)

@AggregateRoot(commands = [MockMountCommand::class])
class MockMountAggregate(val id: String)

@AggregateRoot
data class MockMountCommand(val id: String)

@Suppress("UNUSED_PARAMETER")
class MockAfterCommandAggregate(val id: String) {

    @OnCommand
    fun onCommand(command: CreateCmd): CmdCreated {
        return CmdCreated
    }

    @OnCommand
    fun onCommand(command: UpdateCmd): CmdUpdated {
        return CmdUpdated
    }

    @Order(ORDER_FIRST)
    @AfterCommand
    fun firstAfterCommand(exchange: ServerCommandExchange<Any>): CmdAfter {
        return CmdAfter
    }

    @AfterCommand(include = [CreateCmd::class], exclude = [UpdateCmd::class])
    fun onAfterCommand(exchange: ServerCommandExchange<Any>): CmdAfter {
        require(exchange.getCommandInvokeResult<CmdCreated>() == CmdCreated)
        return CmdAfter
    }

    @Order(ORDER_LAST)
    @AfterCommand
    fun lastAfterCommand(exchange: ServerCommandExchange<Any>): CmdAfter {
        return CmdAfter
    }
}

@CreateAggregate
object CreateCmd
object CmdCreated
object UpdateCmd
object CmdUpdated
object CmdAfter

@Suppress("UNUSED_PARAMETER")
class MockDefaultAfterCommandAggregate(val id: String) {

    fun afterCommand(exchange: ServerCommandExchange<Any>): CmdAfter {
        return CmdAfter
    }
}

@Suppress("UNUSED_PARAMETER")
class MockMultipleAfterCommandAggregate(val id: String) {

    @AfterCommand
    fun onAfterCommand(exchange: ServerCommandExchange<Any>): CmdAfter {
        return CmdAfter
    }

    fun afterCommand(exchange: ServerCommandExchange<Any>): CmdAfter {
        return CmdAfter
    }
}

class MockStateAggregateWithoutCtorCommand(private val state: MockStateAggregateWithoutCtorState)
class MockStateAggregateWithoutCtorState

class MockStateAggregateWithoutRedundantCtorCommand(private val state: MockStateAggregateWithoutRedundantCtorState)
class MockStateAggregateWithoutRedundantCtorState(val id: String, val tenantId: String, val ownerId: String)
