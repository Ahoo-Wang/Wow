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

package me.ahoo.wow.spring.boot

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/** The application's `MongoClient`, on the server the test names. */
@Configuration(proxyBeanMethods = false)
class MongoClientConfiguration {
    @Bean(destroyMethod = "close")
    fun mongoClient(environment: Environment): MongoClient =
        MongoClients.create(environment.getRequiredProperty("test.mongo.url"))
}

/** A host on MongoDB, selected by the application's `MongoClient` bean (decision D110). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoHostTest {
    private val mongo = WowContainers.newMongo().also { it.start() }
    private val host = startHost(
        "wow.mongo.database" to "host_test",
        "wow.log.partitions" to 4,
        "test.mongo.url" to mongo.replicaSetUrl,
        sources = listOf(HostTestApp::class.java, MongoClientConfiguration::class.java, QueryOutcomes::class.java),
    )

    @AfterAll
    fun close() {
        host.close()
        mongo.stop()
    }

    @Test
    fun `the host should run on MongoDB with the append queue`() {
        host.context.getBean(HostBackends::class.java).kind.assert().isEqualTo(BackendKind.MONGO)
        host.node.options.appendQueue.enabled.assert().isTrue()
        host.node.options.routing.assert().isEqualTo(Routing.PARTITIONED)
    }

    @Test
    fun `counts should be cached on MongoDB until a snapshot is written`() {
        host.assertCountCache()
    }

    @Test
    fun `a command should be served and its state read`() {
        val opened = host.post("/bank/account/open-account", """{"owner":"mona"}""")
        opened.status.assert().isEqualTo(200)
        opened.body!!["hop"]["subscription"].asString().assert().isEqualTo("snapshot")
        val id = opened.body!!["ref"]["id"].asString()
        host.post("/bank/account/$id/deposit", """{"amount":3}""").status.assert().isEqualTo(200)
        val state = host.get("/bank/account/$id/state")
        state.status.assert().isEqualTo(200)
        state.body!!["state"]["balance"].asLong().assert().isEqualTo(3)
    }

    @Test
    fun `the snapshots should be queried over HTTP on MongoDB, the declared index created at startup`() {
        val account = AggregateType(BANK, "account")
        host.context.getBean(QueryIndexesLifecycle::class.java).reports.getValue(account).created.assert()
            .contains("ix_account_owner")
        host.context.getBean(MongoClient::class.java).getDatabase("host_test")
            .getCollection("wow_snapshot__bank__account").listIndexes().map { it.getString("name") }.toList()
            .assert().contains("ix_account_owner")
        val opened = host.post("/bank/account/open-account", """{"owner":"query-mona"}""")
        opened.status.assert().isEqualTo(200)
        val listed = host.post(
            "/bank/account/snapshot/query",
            """{"kind":"list","filter":{"op":"EQ","field":"state.owner","value":"query-mona"}}""",
        )
        listed.status.assert().isEqualTo(200)
        listed.body!!.values().map { it["aggregateId"].asString() }.assert()
            .containsExactly(opened.body!!["ref"]["id"].asString())
        host.post("/bank/account/snapshot/query", """{"kind":"count","filter":{"op":"MATCH_ALL"},"limit":1}""")
            .body!!["count"].asLong().assert().isEqualTo(1)
    }
}
