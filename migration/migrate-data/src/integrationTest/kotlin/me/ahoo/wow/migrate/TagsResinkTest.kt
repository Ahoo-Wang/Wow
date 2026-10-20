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

package me.ahoo.wow.migrate

import co.elastic.clients.transport.rest5_client.low_level.Request
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventSinks
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * The tags backfill end to end (decision D259, R4-Q2c): a PostgreSQL log and an Elasticsearch events index written
 * before the tags; the log backfilled, then the documents indexed without tags re-sunk over, which ABAC then sees,
 * and a run again overwrites nothing.
 */
class TagsResinkTest {
    class Parcel(var region: String = "")

    class Moved(val region: String)

    private val context = "tags_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)
    private val model = aggregate<Parcel>(context, "parcel") {
        initial { Parcel() }
        evolve<Moved> { region = it.region }
        tags { mapOf("region" to listOfNotNull(it.region.takeIf(String::isNotEmpty))) }
    }
    private val type = model.type
    private val codec = EventCodec()
    private val log = PostgresEventLog(
        PGSimpleDataSource().apply {
            setURL(WowContainers.postgres.jdbcUrl)
            user = WowContainers.postgres.username
            password = WowContainers.postgres.password
        },
        context,
        PARTITIONS,
    ).also { it.createSchema() }
    private val restClient: Rest5Client =
        Rest5Client.builder(HttpHost.create("http://" + WowContainers.elasticsearch.httpHostAddress)).build()
    private val sink = ElasticsearchEventSinks.of(restClient).sinkOf(context, type.name)
    private val checkpoints = InMemoryCheckpointRepository()
    private val backfiller = TagsBackfiller(MigrationModels(listOf(model)), { log }, checkpoints, ImportOptions(pageSize = 2))

    private fun stream(id: String, version: Int, region: String) = EventStreamRecord(
        ref = type.ref("tenant", id),
        version = version,
        commandId = "$id-$version",
        requestId = "$id-$version",
        commandType = "$context.parcel.Move",
        commitTime = version.toLong(),
        events = listOf(codec.encode(model, Moved(region))),
        scope = Scope.NONE,
    )

    /** [stream] as an old Sink indexed it: the same document, without the tags. */
    private suspend fun indexedBefore(streams: List<EventStreamRecord>) {
        sink.write(
            streams.map { stream ->
                StateEventRecord.of(
                    stream.ref,
                    stream.version,
                    commandId = stream.commandId,
                    requestId = stream.requestId,
                    commandType = stream.commandType,
                    commitTime = stream.commitTime,
                    events = stream.events.map {
                        StateEventPayload.of(it.name, it.revision, WowJson.mapper.readTree(it.payload))
                    },
                    scope = stream.scope,
                )
            },
        )
        // A Sink's write waits for no refresh: make the documents searchable before rewriting them.
        request("POST", "/${sink.index}/_refresh")
        request(
            "POST",
            "/${sink.index}/_update_by_query?refresh=true&conflicts=proceed",
            """{"script":{"source":"ctx._source.remove('tags'); ctx._source.remove('tagsRecorded')"}}""",
        )
    }

    private fun request(method: String, path: String, body: String? = null): JsonNode {
        val response = restClient.performRequest(Request(method, path).also { request -> body?.let(request::setJsonEntity) })
        return WowJson.mapper.readTree(EntityUtils.toString(response.entity))
    }

    /** The streams ABAC shows a caller of `region` [region]: the condition it compiles to on the events index. */
    private fun visible(region: String): List<String> = request(
        "POST",
        "/${sink.index}/_search?version=true",
        """{"size":100,"sort":[{"aggregateId":"asc"},{"version":"asc"}],"query":{"bool":{"filter":[""" +
            """{"exists":{"field":"tagsRecorded"}},{"term":{"tags.region":"$region"}}]}}}""",
    ).path("hits").path("hits").toList().map { "${it.path("_source").path("aggregateId").asString()}@${it.path("_source").path("version").asInt()}" }

    private fun documentVersions(): List<Long> = request(
        "POST",
        "/${sink.index}/_search?version=true",
        """{"size":100,"sort":[{"aggregateId":"asc"},{"version":"asc"}],"query":{"match_all":{}}}""",
    ).path("hits").path("hits").toList().map { it.path("_version").asLong() }

    @Test
    fun `documents indexed without tags are re-sunk from the backfilled log, and a run again changes nothing`(): Unit =
        runBlocking {
            val streams = listOf(stream("p-1", 1, "east"), stream("p-1", 2, "west"), stream("p-2", 1, "east"))
            streams.forEach { log.append(it) }
            indexedBefore(streams)
            sink.untagged(null, 10).size.assert().isEqualTo(3)
            visible("east").assert().isEmpty()
            // The log first: refused while it still holds streams without tags.
            assertThrows<MigrationException> { backfiller.resink(type, sink) }.message!!.assert().contains("backfill it first")
            backfiller.backfill(type).checkpoint.streams.assert().isEqualTo(3)
            backfiller.resink(type, sink).assert().isEqualTo(3)
            sink.untagged(null, 10).assert().isEmpty()
            visible("east").assert().containsExactly("p-1@1", "p-2@1")
            visible("west").assert().containsExactly("p-1@2")
            val versions = documentVersions()
            backfiller.resink(type, sink).assert().isEqualTo(0)
            documentVersions().assert().isEqualTo(versions)
        }

    @Test
    fun `forced, the re-sink leaves the documents of streams still without tags`(): Unit = runBlocking {
        val streams = listOf(stream("p-1", 1, "east"), stream("p-2", 1, "west"))
        log.append(streams[0])
        backfiller.backfill(type)
        log.append(streams[1])
        indexedBefore(streams)
        backfiller.resink(type, sink, force = true).assert().isEqualTo(1)
        sink.untagged(null, 10).map { it.ref.id }.assert().containsExactly("p-2")
        visible("east").assert().containsExactly("p-1@1")
    }

    private companion object {
        const val CONTEXT_SUFFIX = 12
        const val PARTITIONS = 2
    }
}
