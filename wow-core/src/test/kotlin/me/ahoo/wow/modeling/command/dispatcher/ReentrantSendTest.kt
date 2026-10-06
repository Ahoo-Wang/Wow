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

package me.ahoo.wow.modeling.command.dispatcher

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A command sent reactively (no blocking) from the completion of another command, on the dispatcher thread that
 * completed it: a hot aggregate keeps [HOT_OUTSTANDING] commands cycling that way while other commands are sent.
 *
 * Up to 9.2 the hot aggregate's `publishOn` group never left its drain loop (each synchronous completion queued the
 * next hot command into the same group), so commands of other aggregates starved. A mailbox turn is bounded by
 * [me.ahoo.wow.execution.KeyedExecutor.throughput], after which the mailbox yields its worker.
 */
class ReentrantSendTest {

    @Test
    fun `commands keep flowing while handlers resend reactively from the dispatcher thread`() {
        val commandBus = InMemoryCommandBus()
        val completions = ConcurrentHashMap<String, Sinks.Empty<Void>>()
        val running = AtomicBoolean(true)
        val hotHandled = AtomicLong()
        val dispatcher = CommandDispatcher(
            name = "reentrant.CommandDispatcher",
            namedAggregates = setOf(MOCK_AGGREGATE_METADATA.namedAggregate),
            commandBus = commandBus,
            commandHandler = object : CommandHandler {
                override fun handle(
                    exchange: ServerCommandExchange<*>,
                    aggregateMetadata: AggregateMetadata<*, *>,
                ): Mono<Void> = Mono.fromRunnable { completions.remove(exchange.message.id)?.tryEmitEmpty() }
            },
        )
        val runtime = WowRuntime(listOf(dispatcher), Duration.ofSeconds(10), Duration.ZERO)
        runtime.start().block()

        fun command(aggregateId: String): CommandMessage<*> =
            MockCreateAggregate(id = aggregateId, data = "data")
                .toCommandMessage(aggregateId = aggregateId, namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate)

        fun sendLoop() {
            if (!running.get()) {
                return
            }
            val hot = command(HOT_ID)
            val completion = Sinks.empty<Void>()
            completions[hot.id] = completion
            commandBus.send(hot).then(completion.asMono()).subscribe(null, null) {
                hotHandled.incrementAndGet()
                sendLoop()
            }
        }
        try {
            repeat(HOT_OUTSTANDING) { sendLoop() }
            repeat(COLD_COMMANDS) {
                val cold = command(generateGlobalId())
                val completion = Sinks.empty<Void>()
                completions[cold.id] = completion
                commandBus.send(cold).then(completion.asMono()).block(Duration.ofSeconds(5))
            }
            hotHandled.get().assert().isGreaterThan(0)
        } finally {
            running.set(false)
            runtime.stopGracefully().block(Duration.ofSeconds(10))
            commandBus.close()
        }
    }

    private companion object {
        const val HOT_ID = "hot"
        const val HOT_OUTSTANDING = 8
        const val COLD_COMMANDS = 200
    }
}
