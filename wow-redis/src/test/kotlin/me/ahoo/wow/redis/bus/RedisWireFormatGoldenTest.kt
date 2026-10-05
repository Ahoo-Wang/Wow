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

package me.ahoo.wow.redis.bus

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.messaging.transport.TransportMessageBus
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.tck.wire.WireGolden
import me.ahoo.wow.tck.wire.WireSamples
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.ReactiveStreamOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

/**
 * Golden samples of the Redis stream entries the 9.2.x buses append: the stream key and the entry's fields, for the
 * command, domain event and state event buses with their default topic converters.
 *
 * The files under `src/test/resources/wire/v9/` were generated from 9.2.2 and are **frozen v9 wire contracts**: during a
 * rolling upgrade 9.2.x and 9.3.x nodes append to and read from the same streams with the same consumer groups, so the
 * stream key, the single `msg` field and its JSON bytes must not change. Changing a golden needs a design decision, not
 * a re-generation.
 *
 * The entry is captured from the production `send` (`XADD` through `ReactiveStreamOperations.add`), and the golden
 * entry's `msg` field is decoded with the bus's own message type, as the bus's receiver does.
 */
class RedisWireFormatGoldenTest {

    @Test
    fun `command stream entry keeps the v9 wire format`() {
        verify(::RedisCommandBus, WireSamples.commandMessage(), "redis-command-entry.json")
    }

    @Test
    fun `domain event stream entry keeps the v9 wire format`() {
        verify(::RedisDomainEventBus, WireSamples.domainEventStream(), "redis-event-entry.json")
    }

    @Test
    fun `state event stream entry keeps the v9 wire format`() {
        verify(::RedisStateEventBus, WireSamples.stateEvent(), "redis-state-entry.json")
    }

    private fun <M : Message<*, *>> verify(
        busFactory: (ReactiveStringRedisTemplate) -> MessageBus<M, *>,
        message: M,
        golden: String,
    ) {
        val stream = slot<String>()
        val fields = slot<Map<String, String>>()
        val streamOps = mockk<ReactiveStreamOperations<String, String, String>> {
            every { add(capture(stream), capture(fields)) } returns Mono.just(RecordId.of("1-0"))
        }
        val redisTemplate = mockk<ReactiveStringRedisTemplate> {
            every { opsForStream<String, String>() } returns streamOps
        }
        val bus = busFactory(redisTemplate)
        bus.send(message).test().verifyComplete()

        val entry = JsonSerializer.createObjectNode()
        entry.put("stream", stream.captured)
        val fieldsNode = entry.putObject("fields")
        fields.captured.forEach { (key, value) -> fieldsNode.put(key, value) }
        WireGolden.assertMatches(golden, JsonSerializer.writerWithDefaultPrettyPrinter().writeValueAsString(entry))

        val goldenEntry = JsonSerializer.readTree(WireGolden.read(golden)) as ObjectNode
        val encoded = goldenEntry["fields"][MESSAGE_FIELD].asString()
        val decoded = encoded.toObject((bus as TransportMessageBus<*, *>).messageType)
        goldenEntry["stream"].asString().assert().isEqualTo(stream.captured)
        decoded.toJsonString().assert().isEqualTo(encoded)
    }
}
