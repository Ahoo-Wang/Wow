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

package me.ahoo.wow.viewstore.starter

import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.tck.container.WowTestContainers
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import reactor.kotlin.core.publisher.toFlux
import java.util.UUID

/** [ViewStoreHostSpec] on MongoDB: events and snapshots. */
@SpringBootTest(
    classes = [ViewStoreHostApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.application.name=example-service",
        "wow.command.bus.type=in_memory",
        "wow.event.bus.type=in_memory",
        "wow.eventsourcing.state.bus.type=in_memory",
        "wow.kafka.enabled=false",
        "cosid.machine.enabled=true",
        "cosid.machine.distributor.type=manual",
        "cosid.machine.distributor.manual.machine-id=1",
        "cosid.generator.enabled=true",
        "wow.eventsourcing.store.storage=mongo",
        "wow.eventsourcing.snapshot.storage=mongo",
        "wow.view-store.system-views[0].definition-id=orders",
        "wow.view-store.system-views[0].id=orders-open",
        "wow.view-store.system-views[0].title=Open orders",
        "wow.view-store.system-views[0].config={\"kind\":\"record\"}",
    ],
)
class ViewStoreMongoTest : ViewStoreHostSpec() {
    companion object {
        private val DATABASE = "view_store_it_" + UUID.randomUUID().toString().replace("-", "").take(12)

        @JvmStatic
        @DynamicPropertySource
        fun mongo(registry: DynamicPropertyRegistry) {
            registry.add("spring.mongodb.uri") { WowTestContainers.mongo.connectionString + "/" + DATABASE }
            registry.add("spring.mongodb.database") { DATABASE }
        }

        @JvmStatic
        @AfterAll
        fun dropDatabase() {
            MongoClients.create(WowTestContainers.mongo.connectionString).use {
                reactor.core.publisher.Mono.from(it.getDatabase(DATABASE).drop()).block()
            }
        }
    }

    @Test
    fun `the host's own aggregates keep Wow's default request id index`() {
        create("alice")
        MongoClients.create(WowTestContainers.mongo.connectionString).use { mongo ->
            val database = mongo.getDatabase(DATABASE)
            fun indexes(collection: String): List<String> =
                database.getCollection(collection).listIndexes().toFlux().map { (it as Document).getString("name") }
                    .collectList().block()!!
            indexes("view_event_stream").assert().contains("aggregateId_1_requestId_1").doesNotContain("requestId_1")
            indexes("order_event_stream").assert().contains("aggregateId_1_requestId_1").doesNotContain("requestId_1")
        }
    }
}
