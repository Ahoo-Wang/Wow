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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * MG-2: a declared index ends with `_id`, the tie-breaker every snapshot sort ends with, so a sort by its keys under the
 * tenant's equality is the index's order: no blocking sort, and a page reads its own documents. The evidence is the
 * profiler's record of the backend's own find.
 */
class MongoDeclaredIndexOrderTest {
    private val database = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)

    @Test
    fun `a sort by a declared field reads the declared index in order`(): Unit = runBlocking {
        // The profiler this reads runs on a shard's members, not on a mongos.
        assumeFalse(MongoLogFixture.sharded, "The profiler is not on a mongos.")
        val type = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
        val collection = MongoSnapshotStore.ensure(database, type.context, type.name)
        collection.insertMany(
            (0 until DOCUMENTS).map { i ->
                Document("_id", MongoSnapshotStore.keyOf(AggregateRef(type, "t0", "id-%04d".format(i))))
                    .append("tenant_id", "t0")
                    .append("aggregate_id", "id-%04d".format(i))
                    .append("version", 1)
                    .append("schema_fingerprint", "it")
                    .append("state", Document("status", "S${i % STATUSES}"))
                    .append("deleted", false)
                    .append("first_event_time", 0L)
                    .append("last_event_time", i.toLong())
            },
        )
        val storage = MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
        val binding = checkNotNull(storage.binding(type))
        val schema = QueryFixtures.schema(type, binding.storage)
        val status = QueryIndexDeclaration("ix_status", listOf("state.status"))
        storage.manageIndexes(MongoQueryIndexes.plan(type.context, type.name, QueryIndexPlan.of(schema, listOf(status))))
        val namespace = "${MongoLogFixture.DATABASE}.${storage.collection(type.name)}"

        val query = ListQuery(
            AndFilter(listOf(TenantIdFilter("t0"), DeletionFilter(DeletionState.ACTIVE))),
            sort = listOf(Sort(QueryField("state.status"), Sort.Direction.ASC)),
            limit = PAGE,
        )
        database.runCommand(Document("profile", 2))
        val rows = try {
            binding.backend.list(QueryAdmission.Trusted.list(query, schema)).toList()
        } finally {
            database.runCommand(Document("profile", 0))
        }
        rows.map { it.path("state").path("status").stringValue() }.distinct().assert().containsExactly("S0")
        val find = database.getCollection("system.profile")
            .find(Filters.and(Filters.eq("ns", namespace), Filters.eq("op", "query")))
            .first()!!
        find.getString("planSummary").assert().isEqualTo("IXSCAN { tenant_id: 1, state.status: 1, _id: 1 }")
        find.getBoolean("hasSortStage", false).assert().isFalse()
        find.get("docsExamined", Number::class.java).toLong().assert().isLessThanOrEqualTo(PAGE.toLong())
    }

    private companion object {
        const val DOCUMENTS = 500
        const val STATUSES = 5
        const val PAGE = 10
    }
}
