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
package me.ahoo.wow.command

import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.tck.command.CommandBusSpec
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

/**
 * InMemoryCommandBusTest .
 *
 * @author ahoo wang
 */
internal class InMemoryCommandBusTest : CommandBusSpec() {
    override fun createMessageBus(): CommandBus {
        return InMemoryCommandBus()
    }

    @Test
    fun sendMessageWhenNoSubscribers() {
        val commandBus = createMessageBus()
        val command = MockCreateAggregate(
            id = GlobalIdGenerator.generateAsString(),
            data = GlobalIdGenerator.generateAsString(),
        ).toCommandMessage()
        commandBus.send(command)
            .test()
            .verifyComplete()
    }
}
