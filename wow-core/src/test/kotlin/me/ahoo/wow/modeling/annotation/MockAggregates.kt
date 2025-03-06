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
import me.ahoo.wow.command.ServerCommandExchange
import reactor.core.publisher.Mono

class MockAggregate(val id: String)

class MockAggregateWithoutConstructor

class MockAggregateWithAggregateId(@AggregateId val id: String)

abstract class MockAbstractStateAggregate(val id: String)

abstract class MockAbstractCommandAggregate<S : MockAbstractStateAggregate>(val state: S)

class MockStateAggregate(id: String) : MockAbstractStateAggregate(id)
class MockCommandAggregate(state: MockStateAggregate) : MockAbstractCommandAggregate<MockStateAggregate>(state)

@AggregateRoot(commands = [MockMountCommand::class])
class MockMountAggregate(val id: String)

@AggregateRoot
data class MockMountCommand(val id: String)

class MockAfterCommandAggregate(val id: String) {
    @Suppress("UNUSED_PARAMETER")
    @AfterCommand
    fun afterCommand(exchange: ServerCommandExchange<Any>): Mono<Void> {
        return Mono.empty()
    }
}