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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.cursor
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * MG-1: a cursor in the default order, which admission ends with the tenant and the aggregate ID, reads the snapshot
 * key's index in order, page after page, instead of sorting every document of the tenant in memory for each page.
 * The evidence is the profiler's record of the backend's own finds.
 */
class MongoSnapshotKeysetTest {
    private val database = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)

    @Test
    fun `a cursor in the default order reads the key's index, a page's worth of documents each page`(): Unit =
        runBlocking {
            // The profiler this reads runs on a shard's members, not on a mongos.
            assumeFalse(MongoLogFixture.sharded, "The profiler is not on a mongos.")
            val type = seeded()
            val storage = MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
            val binding = checkNotNull(storage.binding(type))
            val schema = QueryFixtures.schema(type, binding.storage)
            val namespace = "${MongoLogFixture.DATABASE}.${storage.collection(type.name)}"

            fun walk(filter: FilterExpression): List<String> = runBlocking {
                val ids = mutableListOf<String>()
                var cursor: String? = null
                do {
                    val query = CursorQuery(filter, size = PAGE, cursor = cursor)
                    val page = binding.backend.cursor(QueryAdmission.Trusted.cursor(query, schema))
                    ids += page.list.map { it.path("tenantId").stringValue() + "/" + it.path("aggregateId").stringValue() }
                    cursor = page.nextCursor
                } while (cursor != null)
                ids
            }

            database.runCommand(Document("profile", 2))
            val ofOne = try {
                walk(TenantIdFilter("t0"))
            } finally {
                database.runCommand(Document("profile", 0))
            }
            ofOne.assert().isEqualTo((0 until PER_TENANT).map { "t0/id-%04d".format(it) })
            val finds = database.getCollection("system.profile")
                .find(Filters.and(Filters.eq("ns", namespace), Filters.eq("op", "query")))
                .toList()
            finds.size.assert().isEqualTo(PER_TENANT / PAGE)
            finds.forEach { find ->
                find.getString("planSummary").assert().isEqualTo("IXSCAN { _id: 1 }")
                find.getBoolean("hasSortStage", false).assert().isFalse()
                // A page reads its own documents and the one past it, not the tenant's.
                find.get("docsExamined", Number::class.java).toLong().assert().isLessThanOrEqualTo(PAGE + 1L)
            }

            // Across tenants, the key orders the tenant before the aggregate ID, as the cursor's sort does.
            walk(MatchAllFilter).assert().isEqualTo(
                (0 until TENANTS).flatMap { tenant -> (0 until PER_TENANT).map { "t$tenant/id-%04d".format(it) } },
            )
        }

    /** [TENANTS] tenants of [PER_TENANT] snapshots each, inserted out of key order, in the store's shape. */
    private fun seeded(): AggregateType {
        val type = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
        val collection = MongoSnapshotStore.ensure(database, type.context, type.name)
        val documents = (0 until TENANTS).flatMap { tenant -> (0 until PER_TENANT).map { tenant to it } }
            .shuffled(java.util.Random(SEED))
            .map { (tenant, i) ->
                val tenantId = "t$tenant"
                val aggregateId = "id-%04d".format(i)
                Document("_id", MongoSnapshotStore.keyOf(tenantId, aggregateId))
                    .append("tenant_id", tenantId)
                    .append("aggregate_id", aggregateId)
                    .append("version", 1)
                    .append("schema_fingerprint", "it")
                    .append("state", Document("status", "S${i % 7}"))
                    .append("deleted", false)
                    .append("first_event_time", 0L)
                    .append("last_event_time", i.toLong())
            }
        collection.insertMany(documents)
        return type
    }

    private companion object {
        const val TENANTS = 2
        const val PER_TENANT = 300
        const val PAGE = 20
        const val SEED = 7L
    }
}
