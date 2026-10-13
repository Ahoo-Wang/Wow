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

package me.ahoo.wow.example.shop.server

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kafka.KafkaOptions
import me.ahoo.wow.kafka.KafkaPartitionedSource
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.spi.transport.EnvelopeCodec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.kafka.ConfluentKafkaContainer
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/**
 * The shop with wow-kafka and `wow.kafka.bootstrap-servers` (decisions D58, D135): every order it commits is
 * published to `wow.example.order.state` with the order's state after it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopKafkaSinkTest {
    private val kafka = ConfluentKafkaContainer("confluentinc/cp-kafka:7.4.0").also { it.start() }
    private val shop = startShop("wow.kafka.bootstrap-servers" to kafka.bootstrapServers, "wow.kafka.topics.ddl" to "auto")
    private val source = KafkaPartitionedSource.create(KafkaOptions(kafka.bootstrapServers))

    @AfterAll
    fun close() {
        source.close()
        shop.close()
        kafka.stop()
    }

    @Test
    fun `a placed order should be published with its state`(): Unit = runBlocking {
        val items = """[{"productId":"apple","price":2.50,"quantity":2},{"productId":"pear","price":4.00,"quantity":1}]"""
        val address = """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1"}"""
        val created = shop.post("/example/order/create-order", """{"items":$items,"address":$address}""", "alice")
        created.status.assert().isEqualTo(200)
        val id = created.json["ref"]["id"].asString()

        val topic = PublishedManifest.defaultTopic("example", "order")
        val record = withTimeout(60.seconds) {
            source.read(topic, 0, 0).map { EnvelopeCodec.stateEventOf(it.value) }.first { it.ref.id == id }
        }
        record.version.assert().isEqualTo(1)
        record.events.single().name.assert().isEqualTo("example.order.OrderCreated")
        record.scope.ownerId.assert().isEqualTo("alice")
        val state = record.state!!
        state.schemaFingerprint.assert().isNotBlank()
        state.state["status"].asString().assert().isEqualTo("CREATED")
        state.state["totalAmount"].decimalValue().assert().isEqualByComparingTo(BigDecimal("9.00"))
    }
}
