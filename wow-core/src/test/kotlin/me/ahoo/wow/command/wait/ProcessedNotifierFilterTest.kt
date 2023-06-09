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

package me.ahoo.wow.command.wait

import me.ahoo.wow.command.CommandMessage
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class ProcessedNotifierFilterTest {

    @Suppress("UNCHECKED_CAST")
    @Test
    fun filter() {
        val processedNotifierFilter = ProcessedNotifierFilter(LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar))
        val command = MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
            .asCommandMessage() as CommandMessage<*>
        val exchange = SimpleServerCommandExchange(command)
        val chain = FilterChainBuilder<ServerCommandExchange<*>>().build()
        processedNotifierFilter.filter(exchange, chain)
            .test()
            .verifyComplete()
    }
}
