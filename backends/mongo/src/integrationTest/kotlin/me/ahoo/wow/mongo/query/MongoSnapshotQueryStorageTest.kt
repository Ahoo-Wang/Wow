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

import com.mongodb.ReadPreference
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.StorageTimeoutException
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class MongoSnapshotQueryStorageTest {
    private val database = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)

    private fun storage(type: AggregateType, options: MongoQueryOptions = MongoQueryOptions()) =
        MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context, options)

    private fun store(type: AggregateType) =
        MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)

    private suspend fun schema(type: AggregateType): QueryModelSchema = QueryFixtures.schema(type, storage(type).create(type).storage)

    @Test
    fun `admission rejects what MongoDB declines`(): Unit = runBlocking {
        val type = newType()
        store(type).createCollection(type.name)
        val schema = schema(type)
        fun rejects(filter: FilterExpression, sort: List<Sort> = emptyList()) {
            val error = runCatching { QueryAdmission.Trusted.list(ListQuery(filter, sort = sort), schema) }.exceptionOrNull()
            (error is QuerySchemaValidationException).assert().describedAs("$error").isTrue()
        }
        rejects(GreaterThanFilter(QueryField("state.issuedAt"), "2026-01-01T00:00:00Z"))
        rejects(EqualFilter(QueryField("state.issuedAt"), "2026-01-01T00:00:00Z"))
        rejects(MatchAllFilter, listOf(Sort(QueryField("state.issuedAt"), Sort.Direction.ASC)))
        // MongoDB cannot sort by two arrays on independent paths.
        rejects(MatchAllFilter, listOf(Sort(QueryField("state.keywords"), Sort.Direction.ASC), Sort(QueryField("state.scores"), Sort.Direction.ASC)))
        // Full-text search needs a text index.
        rejects(SearchFilter("wow"))
        val cursor = runCatching {
            QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("state.keywords"), Sort.Direction.ASC))), schema)
        }.exceptionOrNull()
        ((cursor as QuerySchemaValidationException).violation is QueryViolation.UnsupportedCapability).assert().isTrue()
        // A date string still exists.
        QueryAdmission.Trusted.list(ListQuery(ExistsFilter(QueryField("state.issuedAt"))), schema)
        Unit
    }

    @Test
    fun `a collection not created yet is provisional and empty`(): Unit = runBlocking {
        val type = newType()
        val binding = storage(type).create(type)
        val provisional = QueryFixtures.schema(type, binding.storage)
        provisional.provisional.assert().isTrue()
        val backend = binding.backend
        val status = QueryField("state.status")
        backend.list(QueryAdmission.Trusted.list(ListQuery(EqualFilter(status, "NEW")), provisional)).toList().assert().isEmpty()
        backend.countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter, 5), provisional))
            .assert().isEqualTo(CountResult(0, exact = true))
        backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, total = TotalMode.EXACT), provisional)).let { page ->
            page.list.assert().isEmpty()
            page.total.assert().isEqualTo(0L)
        }
        backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, size = 2), provisional)).list.assert().isEmpty()
        backend.aggregate(
            QueryAdmission.Trusted.aggregate(
                AggregationQuery(groupBy = listOf(AggregationGroup.Terms(status, "status")), metrics = listOf(AggregationMetric.Count("count"))),
                provisional,
            ),
        ).toList().assert().isEmpty()
        // Reading created nothing, so the store still claims the collection.
        database.listCollectionNames().into(ArrayList()).contains(storage(type).collection(type.name)).assert().isFalse()

        // Once the aggregate's first snapshot creates the collection, its structures prove the facts.
        store(type).save(QueryFixtures.snapshot(type, "a", """{"status":"NEW"}"""))
        QueryFixtures.schema(type, binding.storage).provisional.assert().isFalse()
    }

    @Test
    fun `the snapshot queries may read from a secondary (review R3-4-2)`(): Unit = runBlocking {
        val type = newType()
        store(type).save(QueryFixtures.snapshot(type, "a", """{"status":"NEW"}""")).assert().isTrue()
        val secondary = MongoSnapshotQueryStorage(
            MongoLogFixture.client,
            MongoLogFixture.DATABASE,
            type.context,
            readPreference = ReadPreference.secondaryPreferred(),
        ).create(type).backend
        // The test replica set has one member: the read falls back to the primary.
        secondary.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema(type))).toList().assert().hasSize(1)
    }

    @Test
    fun `a storage serves only its own context`(): Unit = runBlocking {
        val type = newType()
        assertThrownBy<IllegalArgumentException> { storage(type).create(AggregateType("elsewhere", "order")) }
        storage(type).collection(type.name).assert().isEqualTo(MongoSnapshotStore.collectionName(type.context, type.name))
        val elsewhere = MongoQueryIndexes.plan("elsewhere", "order", schema(type), emptyList())
        assertThrownBy<IllegalArgumentException> { storage(type).manageIndexes(elsewhere) }
    }

    @Test
    fun `a text index enables full-text search`(): Unit = runBlocking {
        val type = newType()
        val store = store(type)
        store.createCollection(type.name)
        database.getCollection(storage(type).collection(type.name)).createIndex(Indexes.text("state.name"))
        QueryFixtures.orders(type).forEach { store.save(it) }
        val binding = storage(type).create(type)
        val schema = QueryFixtures.schema(type, binding.storage)
        schema.capabilities.assert().contains(QueryCapability.FULL_TEXT_TERMS)
        binding.backend.list(QueryAdmission.Trusted.list(ListQuery(SearchFilter("delta")), schema)).toList()
            .map { it.path("aggregateId").stringValue() }.assert().containsExactly("d")
    }

    @Test
    fun `a command MongoDB stops at maxTimeMS is the query timeout`(): Unit = runBlocking {
        val type = seeded
        val backend = storage(type).create(type).backend
        val schema = schema(type)
        val timeout = Duration.ofMillis(1)
        // A filter and a sort no index serves, so every command reads the whole collection.
        val slow = GreaterThanFilter(AMOUNT, -1)
        val byAmount = listOf(Sort(AMOUNT, Sort.Direction.DESC))
        suspend fun times(block: suspend () -> Unit) {
            val error = runCatching { block() }.exceptionOrNull()
            (error is StorageTimeoutException).assert().describedAs("$error").isTrue()
            (error as StorageTimeoutException).timeout.assert().isEqualTo(timeout)
        }
        times { backend.count(QueryAdmission.Trusted.count(CountQuery(slow), schema).withTimeout(timeout)) }
        times { backend.stream(QueryAdmission.Trusted.list(ListQuery(slow, sort = byAmount), schema).withTimeout(timeout)).toList() }
        times {
            backend.page(
                QueryAdmission.Trusted.paged(PagedQuery(slow, sort = byAmount, total = TotalMode.EXACT), schema).withTimeout(timeout),
                PageWindow.Offset(0, 1, withTotal = true),
            )
        }
        times {
            backend.page(
                QueryAdmission.Trusted.cursor(CursorQuery(slow, sort = byAmount, size = 1), schema).withTimeout(timeout),
                PageWindow.Keyset(null, 1),
            )
        }
        times {
            val query = AggregationQuery(slow, groupBy = listOf(AggregationGroup.Terms(STATUS, "status")), metrics = listOf(AggregationMetric.Count("count")))
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema).withTimeout(timeout), GroupWindow.All).toList()
        }
        // Without a timeout the same command runs to the end, and a bounded count stops one past its limit.
        backend.count(QueryAdmission.Trusted.count(CountQuery(slow, limit = 10), schema)).assert().isEqualTo(11L)
    }

    @Test
    fun `auto creates the indexes, validate and none only look`(): Unit = runBlocking {
        val type = newType()
        val schema = schema(type)
        val indexes = MongoQueryIndexes.plan(type.context, type.name, schema, listOf(STATUS_INDEX))
        val names = indexes.map { it.name }
        storage(type, MongoQueryOptions(Ddl.NONE)).manageIndexes(indexes).assert().isEqualTo(MongoQueryIndexReport())
        // Before the aggregate's first snapshot the collection does not exist: every index is missing.
        storage(type, MongoQueryOptions(Ddl.VALIDATE)).manageIndexes(indexes)
            .assert().isEqualTo(MongoQueryIndexReport(missing = names))
        storage(type).manageIndexes(indexes).assert().isEqualTo(MongoQueryIndexReport(created = names, missing = names))
        // auto made sure of the collection as the store does, so the store opens it.
        store(type).save(QueryFixtures.snapshot(type, "a", """{"status":"NEW"}""")).assert().isTrue()
        storage(type).manageIndexes(indexes).assert().isEqualTo(MongoQueryIndexReport())
        storage(type, MongoQueryOptions(Ddl.VALIDATE)).manageIndexes(indexes).assert().isEqualTo(MongoQueryIndexReport())

        // An index of the same name and another definition is reported, and rebuilt by auto.
        val collection = database.getCollection(storage(type).collection(type.name))
        collection.dropIndex(STATUS_INDEX.name)
        collection.createIndex(Indexes.ascending("tenant_id", "state.status"), IndexOptions().name(STATUS_INDEX.name))
        storage(type, MongoQueryOptions(Ddl.VALIDATE)).manageIndexes(indexes)
            .assert().isEqualTo(MongoQueryIndexReport(invalid = listOf(STATUS_INDEX.name)))
        storage(type).manageIndexes(indexes)
            .assert().isEqualTo(MongoQueryIndexReport(created = listOf(STATUS_INDEX.name), invalid = listOf(STATUS_INDEX.name)))
        storage(type, MongoQueryOptions(Ddl.VALIDATE)).manageIndexes(indexes).assert().isEqualTo(MongoQueryIndexReport())
        // A collection the store created has the scope indexes already.
        val fresh = newType()
        store(fresh).createCollection(fresh.name)
        val planned = MongoQueryIndexes.plan(fresh.context, fresh.name, schema, emptyList())
        storage(fresh, MongoQueryOptions(Ddl.VALIDATE)).manageIndexes(planned).assert().isEqualTo(MongoQueryIndexReport())
    }

    @Test
    fun `queries use the scope indexes and the declared index`(): Unit = runBlocking {
        val type = seeded
        val schema = schema(type)
        val collection = database.getCollection(storage(type).collection(type.name))
        fun scoped(vararg filters: FilterExpression): FilterExpression =
            AndFilter(listOf(TenantIdFilter("t3"), DeletionFilter(DeletionState.ACTIVE)) + filters)
        fun filter(filter: FilterExpression): Bson {
            val admitted = QueryAdmission.Trusted.list(ListQuery(filter, limit = 10), schema)
            return MongoMatchCompiler.compile(admitted.query.filter, admitted)
        }
        fun plan(filter: FilterExpression): String = collection.find(filter(filter)).limit(10).explain().winningPlan()

        plan(scoped(OwnerIdFilter("owner-7"))).assert().contains("\"indexName\": \"owner_ix\"")
        plan(scoped(SpaceIdFilter("space-7"))).assert().contains("\"indexName\": \"space_ix\"")
        plan(scoped(EqualFilter(STATUS, "S7"), GreaterThanFilter(AMOUNT, 100))).assert()
            .contains("\"indexName\": \"${STATUS_INDEX.name}\"")
        // countDocuments with a limit runs as this pipeline.
        val counted = collection.aggregate(
            listOf(
                Aggregates.match(filter(scoped(EqualFilter(STATUS, "S7")))),
                Aggregates.limit(11),
                Aggregates.group(null, Accumulators.sum("n", 1)),
            ),
        ).explain().toJson()
        counted.assert().contains("\"indexName\": \"${STATUS_INDEX.name}\"")
    }

    @Test
    fun `a query of one tenant in the default order reads the key's index, the tenant's range of it`(): Unit = runBlocking {
        // Decision D277: no index but `_id_` holds the key; the backend bounds it to the tenant and sorts by it.
        val type = seeded
        val schema = schema(type)
        val collection = database.getCollection(storage(type).collection(type.name))
        val admitted = QueryAdmission.Trusted.list(ListQuery(TenantIdFilter("t3"), limit = 10), schema)
        val filter = Filters.and(MongoSnapshotStore.tenantKeys("t3"), MongoMatchCompiler.compile(admitted.query.filter, admitted))
        val found = collection.find(filter).sort(Sorts.ascending("_id")).limit(10)
        found.explain().winningPlan().assert().contains("\"indexName\": \"_id_\"").doesNotContain("\"SORT\"")
        found.map { it.getString("tenant_id") }.toList().assert().hasSize(10).allMatch { it == "t3" }
    }

    private fun Document.winningPlan(): String =
        get("queryPlanner", Document::class.java).get("winningPlan", Document::class.java).toJson()

    private companion object {
        val STATUS = QueryField("state.status")
        val AMOUNT = QueryField("state.amount")
        val STATUS_INDEX = QueryIndexDeclaration("ix_order_state_status", listOf("state.status", "state.amount"))
        const val DOCUMENTS = 200_000
        const val BATCH = 10_000

        fun newType(): AggregateType = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")

        /**
         * One aggregate type of [DOCUMENTS] snapshots, written in the store's shape, over ten tenants, 500 owners, 300
         * spaces and 50 statuses, every 20th deleted, with the declared status index.
         */
        val seeded: AggregateType by lazy {
            val type = newType()
            val database = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            val collection = MongoSnapshotStore.ensure(database, type.context, type.name)
            (0 until DOCUMENTS).chunked(BATCH).forEach { batch ->
                collection.insertMany(
                    batch.map { i ->
                        Document("_id", MongoSnapshotStore.keyOf(type.ref("t${i % 10}", "id-$i")))
                            .append("tenant_id", "t${i % 10}")
                            .append("aggregate_id", "id-$i")
                            .append("version", 1)
                            .append("schema_fingerprint", "it")
                            .append("state", Document("status", "S${i % 50}").append("amount", i).append("name", "n$i"))
                            .append("owner_id", "owner-${i % 500}")
                            .append("space_id", "space-${i % 300}")
                            .append("deleted", i % 20 == 0)
                            .append("first_event_time", 0L)
                            .append("last_event_time", i.toLong())
                            .append("tags", Document())
                    },
                )
            }
            val schema = runBlocking {
                QueryFixtures.schema(type, MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context).create(type).storage)
            }
            MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
                .manageIndexes(MongoQueryIndexes.plan(type.context, type.name, schema, listOf(STATUS_INDEX)))
            type
        }
    }
}
