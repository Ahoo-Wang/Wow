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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * MG-3: every index of a log collection leads with the partition, so an `events` query that pins its tenant and
 * aggregates reads the version key only once it is given their partitions. The evidence is the profiler's record of
 * the backend's own finds.
 */
class MongoEventQueryPartitionTest {
    private val type = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
    private val database = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)

    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                type.context,
                aggregates = listOf(AggregateManifest(type.name, state = StateManifest(Manifest.schemaRef("it.S")))),
            ),
        ),
        defs = mapOf("it.S" to mapOf("type" to "object")),
    )

    private fun stream(aggregate: Int, version: Int) = EventStreamRecord(
        ref = type.ref("t1", "o-$aggregate"),
        version = version,
        commandId = "c-$aggregate-$version",
        requestId = "r-$aggregate-$version",
        commandType = "${type.context}.order.Change",
        commitTime = version.toLong(),
        events = listOf(EventRecord("${type.context}.order.Changed", 1, "{}")),
        scope = Scope.NONE,
    )

    @Test
    fun `a query of pinned aggregates reads the version key of their partitions`(): Unit = runBlocking {
        // The profiler this reads runs on a shard's members, not on a mongos.
        assumeFalse(MongoLogFixture.sharded, "The profiler is not on a mongos.")
        val log = MongoLogFixture.log(PARTITIONS, type.context, listOf(type.name))
        for (version in 1..VERSIONS) {
            log.appendAll((0 until AGGREGATES).map { Append.of(stream(it, version)) })
        }
        val storage = MongoEventQueryStorage(
            MongoLogFixture.client,
            MongoLogFixture.DATABASE,
            type.context,
            partitions = { PARTITIONS },
        )
        val binding = checkNotNull(storage.binding(type))
        val schema = QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
            .compile(QuerySchemaContext(type, QueryModel.EVENTS), binding.storage)
            .schema()
        val namespace = "${MongoLogFixture.DATABASE}.${storage.collection(type.name)}"
        fun streams(filter: FilterExpression): List<String> = runBlocking {
            binding.backend.list(QueryAdmission.Trusted.list(ListQuery(filter, limit = 100), schema)).toList()
                .map { it.path("aggregateId").stringValue() + "@" + it.path("version").intValue() }
        }

        database.runCommand(Document("profile", 2))
        val (one, two) = try {
            streams(AndFilter(listOf(TenantIdFilter("t1"), AggregateIdFilter("o-7")))) to
                streams(AndFilter(listOf(TenantIdFilter("t1"), AggregateIdsFilter(listOf("o-7", "o-8")))))
        } finally {
            database.runCommand(Document("profile", 0))
        }
        one.assert().containsExactlyElementsOf((1..VERSIONS).map { "o-7@$it" })
        two.assert().hasSize(2 * VERSIONS)
        val finds = database.getCollection("system.profile")
            .find(Filters.and(Filters.eq("ns", namespace), Filters.eq("op", "query")))
            .toList()
        finds.size.assert().isEqualTo(2)
        finds.forEach { find ->
            find.getString("planSummary").assert().startsWith("IXSCAN { partition: 1, aggregate_id: 1")
            // The pinned aggregates' streams, not the collection's.
            find.get("docsExamined", Number::class.java).toLong().assert().isLessThanOrEqualTo(2L * VERSIONS)
        }
    }

    private companion object {
        const val PARTITIONS = 4
        const val AGGREGATES = 200
        const val VERSIONS = 5
    }
}
