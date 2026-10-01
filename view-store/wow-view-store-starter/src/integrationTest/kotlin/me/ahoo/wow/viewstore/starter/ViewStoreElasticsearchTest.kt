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

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.container.WowTestContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * [ViewStoreHostSpec] with the events and the snapshots on Elasticsearch, on Wow's own index templates and the view
 * store's index definitions only: no mapping is made by hand. The replay queries the view's event stream.
 */
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
        "wow.eventsourcing.store.storage=elasticsearch",
        "wow.eventsourcing.snapshot.storage=elasticsearch",
        "wow.view-store.system-views[0].definition-id=orders",
        "wow.view-store.system-views[0].id=orders-open",
        "wow.view-store.system-views[0].title=Open orders",
        "wow.view-store.system-views[0].config={\"kind\":\"record\"}",
    ],
)
class ViewStoreElasticsearchTest : ViewStoreHostSpec() {
    companion object {
        private val DATABASE = "view_store_es_it_" + UUID.randomUUID().toString().replace("-", "").take(12)
        private const val VIEW_INDEX = "wow.view-store.view.snapshot"
        private const val PREFERENCES_INDEX = "wow.view-store.view_preferences.snapshot"
        private const val VIEW_EVENTS_INDEX = "wow.view-store.view.es"

        @JvmStatic
        @DynamicPropertySource
        fun storage(registry: DynamicPropertyRegistry) {
            registry.add("spring.mongodb.uri") { WowTestContainers.mongo.connectionString + "/" + DATABASE }
            registry.add("spring.mongodb.database") { DATABASE }
            val elasticsearch = WowTestContainers.elasticsearch
            registry.add("spring.elasticsearch.uris") { "https://${elasticsearch.httpHostAddress}" }
            registry.add("spring.elasticsearch.username") { WowTestContainers.ELASTIC_USER }
            registry.add("spring.elasticsearch.password") { WowTestContainers.elasticPassword }
            registry.add("spring.ssl.bundle.pem.wow-it-elasticsearch.truststore.certificate") {
                String(elasticsearch.caCertAsBytes().orElseThrow(), Charsets.UTF_8)
            }
            registry.add("spring.elasticsearch.restclient.ssl.bundle") { "wow-it-elasticsearch" }
        }

        @JvmStatic
        @AfterAll
        fun dropStorage() {
            MongoClients.create(WowTestContainers.mongo.connectionString).use {
                reactor.core.publisher.Mono.from(it.getDatabase(DATABASE).drop()).block()
            }
        }
    }

    @Autowired
    private lateinit var elasticsearchClient: ReactiveElasticsearchClient

    private fun properties(index: String): Map<String, Property> =
        elasticsearchClient.indices().getMapping(GetMappingRequest.of { it.index(index) }).block()!!
            .mappings().getValue(index).mappings().properties()

    private fun Map<String, Property>.at(path: String): Property {
        val head = path.substringBefore('.')
        val property = getValue(head)
        if (head == path) return property
        val children = when {
            property.isObject -> property.`object`().properties()
            property.isNested -> property.nested().properties()
            else -> error("[$head] has no properties")
        }
        return children.at(path.substringAfter('.'))
    }

    @Test
    fun `the view store's indices are created at startup from its index definitions`() {
        val view = properties(VIEW_INDEX)
        view.at("state.config").`object`().dynamic().assert().isNotNull()
        view.at("state.config.panels").isNested.assert().isTrue()
        listOf("state.definitionId", "state.appId", "state.config.kind", "state.config.panels.instanceId")
            .forEach { path ->
                view.at(path).keyword().ignoreAbove().assert().isNull()
            }
        properties(PREFERENCES_INDEX).at("state.lastTabs").`object`().enabled().assert().isFalse()
        // The replay's filters: Wow's event-stream template stores an event's body unindexed.
        val events = properties(VIEW_EVENTS_INDEX)
        events.at("body").isNested.assert().isTrue()
        events.at("body.body").`object`().dynamic().assert().isNotNull()
        listOf("requestId", "tenantId", "ownerId", "body.bodyType", "body.body.audience", "body.body.toOwnerId")
            .forEach { path ->
                events.at(path).keyword().ignoreAbove().assert().isNull()
            }
    }

    @Test
    fun `configs that put values of different types under one key are all written and listed`() {
        val definitionId = "conflicts-" + UUID.randomUUID()
        val configs = listOf(
            """{"kind":"record","filter":{"status":"OPEN"},"columns":["a"]}""",
            """{"kind":"record","filter":{"status":3},"columns":[{"field":"a"}]}""",
            """{"kind":"record","filter":{"status":["OPEN",2,null]},"columns":"a"}""",
            """{"kind":"record","filter":{"status":{"in":["OPEN"],"nested":{"deep":true}}},"columns":7}""",
            """{"kind":"analysis","filter":"status","columns":null}""",
        ) + (1..50).map { """{"kind":"record","filter":{"field$it":{"op":"eq","value":$it}}}""" }
        val ids = configs.map { config ->
            write(
                "POST",
                "$SCOPE/alice/view",
                """{"definitionId":"$definitionId","title":"View","config":$config}""",
            ).expectStatus().isOk
                .expectBody(tools.jackson.databind.JsonNode::class.java).returnResult().responseBody!!
                .get("aggregateId").stringValue()
        }

        query(
            "$SCOPE/alice/view/snapshot/list",
            listQuery {
                filter { "state.definitionId" eq definitionId }
                projection { include("aggregateId", "state.config.kind") }
            }.toJsonString(),
        ).expectStatus().isOk
            .expectBody(tools.jackson.databind.JsonNode::class.java).returnResult().responseBody!!
            .let { rows -> (0 until rows.size()).map { rows.get(it).get("aggregateId").stringValue() } }
            .assert().containsExactlyInAnyOrderElementsOf(ids)
        // Nothing of a config but the paths the store queries became a field.
        properties(VIEW_INDEX).at("state.config").`object`().properties().keys
            .assert().containsExactlyInAnyOrder("kind", "panels")
    }

    @Test
    fun `a definition without views lists nothing`() {
        query(
            "$SCOPE/alice/view/snapshot/list",
            listQuery { filter { "state.definitionId" eq "none-" + UUID.randomUUID() } }.toJsonString(),
        ).expectStatus().isOk.expectBody().json("[]")
        query(
            "$SCOPE/$SHARED/view_preferences/snapshot/list",
            listQuery { filter { "state.definitionId" eq "none-" + UUID.randomUUID() } }.toJsonString(),
        ).expectStatus().isOk.expectBody().json("[]")
    }
}
