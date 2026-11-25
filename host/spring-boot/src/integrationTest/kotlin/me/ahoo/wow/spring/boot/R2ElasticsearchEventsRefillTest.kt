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

import co.elastic.clients.transport.rest5_client.low_level.Request
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * #7097: `operate/elasticsearch.md` (Rebuild) says the events index "is filled again by starting its sink subscription
 * from the beginning of the log", after its concrete index `<name>--1` is deleted to empty it. The sink's subscription
 * is named `wow.elasticsearch-sink.<aggregate>` whatever its index, and no setting or operation starts it from the
 * beginning, so a host started again after the deletion resumes from its checkpoint: the index holds only the commits
 * after it.
 */
class R2ElasticsearchEventsRefillTest {
    private val prefix = "refill" + UUID.randomUUID().toString().take(SUFFIX)

    @Test
    fun `an events index emptied as the page says should be filled again from the log`() {
        val index = "${prefix}_wow_events__bank__account"
        val id = startHost(*properties(), sources = sources()).use { host ->
            val opened = host.post("/bank/account/open-account", """{"owner":"refill"}""")
            opened.status.assert().isEqualTo(OK)
            val id = opened.body!!["ref"]["id"].asString()
            repeat(DEPOSITS) { host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(OK) }
            val client = host.context.getBean(Rest5Client::class.java)
            eventually { client.count(index) == 1L + DEPOSITS }
            // The page's way to empty it: delete the concrete index, which Elasticsearch deletes through no alias.
            client.performRequest(Request("DELETE", "/$index--1")).statusCode.assert().isEqualTo(OK)
            id
        }
        // The only start a host has: it creates the pair again (ddl auto), and the sink goes on.
        startHost(*properties(), sources = sources()).use { host ->
            val client = host.context.getBean(Rest5Client::class.java)
            // Not made again at the start: the next write makes the pair (ddl auto).
            client.exists(index).assert().describedAs("the events index after the host started again").isFalse()
            host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(OK)
            // Its latest commit indexed: a sink started from the beginning would have written every earlier one first.
            eventually { client.versions(index).contains(DEPOSITS + 2) }
            client.versions(index).assert().describedAs("the versions the events index holds")
                .containsExactlyInAnyOrderElementsOf((1..DEPOSITS + 2).toList())
        }
    }

    /** The documents of [index], none while it does not exist. */
    private fun Rest5Client.count(index: String): Long {
        performRequest(Request("POST", "/$index/_refresh"))
        val response = performRequest(Request("GET", "/$index/_count"))
        if (response.statusCode == NOT_FOUND) return 0L
        return mapper.readTree(response.entity.content)["count"].asLong()
    }

    /** The versions of the commits [index] holds, none while it does not exist. */
    private fun Rest5Client.versions(index: String): List<Int> {
        performRequest(Request("POST", "/$index/_refresh"))
        val response = performRequest(
            Request("POST", "/$index/_search").apply {
                setJsonEntity("""{"size":100,"_source":false,"docvalue_fields":["version"]}""")
            },
        )
        if (response.statusCode == NOT_FOUND) return emptyList()
        val body = response.entity.content.readAllBytes().decodeToString()
        val hits = checkNotNull(mapper.readTree(body)["hits"]?.get("hits")) { "No hits in $body" }
        return hits.values().map { checkNotNull(it["fields"]?.get("version")?.get(0)) { "No version in $it" }.asInt() }
    }

    /** Whether [index] exists, as an alias or an index. */
    private fun Rest5Client.exists(index: String): Boolean =
        performRequest(Request("HEAD", "/$index")).statusCode == OK

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in time." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun properties(): Array<Pair<String, Any>> = arrayOf(
        "wow.log.partitions" to 2,
        "wow.stores.mongo.type" to "mongo",
        "wow.stores.mongo.database" to "refill_$prefix",
        "test.mongo.url" to WowContainers.mongoUrl,
        "wow.log.store" to "mongo",
        "wow.stores.es.type" to "elasticsearch",
        "wow.stores.es.index-prefix" to prefix,
        "wow.aggregates.[$BANK.account].query.events.store" to "es",
    )

    private fun sources(): List<Class<*>> = listOf(
        HostTestApp::class.java,
        ElasticsearchOwnSnapshotsTest.ElasticsearchClientConfiguration::class.java,
        MongoClientConfiguration::class.java,
    )

    private companion object {
        val mapper: JsonMapper = JsonMapper.builder().build()
        const val OK = 200
        const val NOT_FOUND = 404
        const val DEPOSITS = 2
        const val SUFFIX = 8
        const val TIMEOUT_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
