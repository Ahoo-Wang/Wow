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

package me.ahoo.wow.kafka

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.ExactJson
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.StateView
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.EnvelopeHeaders
import me.ahoo.wow.spi.transport.SourceMessage
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * State events on a real broker (design 08 §8, decision D58): the envelope the Kafka Sink writes, keyed by the
 * aggregate ref, read back whole through the source.
 */
class KafkaStateEventTest {
    private val order = AggregateType("shop", "order")

    private fun record(id: String, version: Int) = StateEventRecord(
        ref = order.ref("t", id),
        version = version,
        commandId = "c-$id-$version",
        requestId = "r-$id-$version",
        commandType = "shop.order.Pay",
        commitTime = 1_000L + version,
        events = listOf(StateEventPayload("shop.order.Paid", 1, WowJson.mapper.readTree("""{"amount":2.50}"""))),
        state = StateView(version, "fingerprint", WowJson.mapper.readTree(ExactJson.NUMBERS)),
        scope = Scope(ownerId = "alice"),
    )

    private suspend fun readAll(topic: String): List<Pair<Int, SourceMessage>> =
        source.partitions(topic).flatMap { partition ->
            val count = source.offsets(topic, partition).end.toInt()
            if (count == 0) {
                emptyList()
            } else {
                withTimeout(30.seconds) { source.read(topic, partition, 0).take(count).toList() }.map { partition to it }
            }
        }

    @Test
    fun `an aggregate's state events should be read back in order from one partition, numbers exact`(): Unit =
        runBlocking {
            val topic = KafkaFixture.newTopic(3)
            val records = (1..3).map { record("o-1", it) } + record("o-2", 1)
            producer.send(topic, records.map(EnvelopeCodec::message))
            val read = readAll(topic)
            read.size.assert().isEqualTo(4)
            val ofO1 = read.filter { it.second.key == "shop.order/t/o-1" }
            ofO1.map { it.first }.toSet().assert().hasSize(1)
            val decoded = ofO1.map { EnvelopeCodec.stateEventOf(it.second.value) }
            decoded.assert().containsExactlyElementsOf(records.take(3))
            ExactJson.of(WowJson.mapper.writeValueAsString(decoded.first().state!!.state))
                .assert().isEqualTo(ExactJson.of(ExactJson.NUMBERS))
            ofO1.first().second.headers.assert().isEqualTo(
                mapOf(EnvelopeHeaders.FORMAT to "1", EnvelopeHeaders.KIND to "state-event", EnvelopeHeaders.TYPE to "shop.order"),
            )
        }

    @Test
    fun `a record written again should be told apart by its id`(): Unit = runBlocking {
        val topic = KafkaFixture.newTopic(1)
        val record = record("o-3", 1)
        // At least once (decision D45): a retry after a lost acknowledgement writes the record again.
        producer.send(topic, EnvelopeCodec.message(record))
        producer.send(topic, EnvelopeCodec.message(record))
        val ids = readAll(topic).map { EnvelopeCodec.stateEventOf(it.second.value).id }
        ids.assert().containsExactly("shop.order/t/o-3@1", "shop.order/t/o-3@1")
        ids.distinct().assert().containsExactly(record.id)
    }

    companion object {
        private val producer by lazy { KafkaTopicProducer.create(KafkaFixture.options) }
        private val source by lazy { KafkaPartitionedSource.create(KafkaFixture.options) }

        @JvmStatic
        @AfterAll
        fun close() {
            producer.close()
            source.close()
        }
    }
}
