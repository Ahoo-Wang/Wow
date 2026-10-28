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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.query.tck.QueryBackendSpec
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.tck.ExactJson
import org.junit.jupiter.api.Test
import java.io.StringReader

/** How the snapshot queries manage an index: its creation, its state mapping under each DDL mode, a missing index. */
class ElasticsearchSnapshotQueriesTest {
    private val type = AggregateType(ElasticsearchFixture.newContext(), "order")
    private val index = ElasticsearchFixture.names.snapshots(type.context, type.name)

    private fun queries(ddl: Ddl = Ddl.AUTO) =
        ElasticsearchSnapshotQueries(ElasticsearchFixture.client, ElasticsearchFixture.names, ddl)

    private suspend fun schema(queries: ElasticsearchSnapshotQueries): QueryModelSchema {
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        return DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, QueryBackendSpec.DECLARATION)))),
            queries.binding(type).storage,
        ).schema()
    }

    private fun snapshot(id: String, state: String) = SnapshotRecord.of(
        ref = type.ref("tenant", id),
        version = 1,
        schemaFingerprint = "it",
        state = state,
        firstEventTime = 1,
        lastEventTime = 1,
    )

    private val store get() = ElasticsearchFixture.store().snapshots(type.context) as ElasticsearchSnapshotStore

    @Test
    fun `a missing index answers nothing against the schema it will be created with`(): Unit = runBlocking {
        val queries = queries()
        val schema = schema(queries)
        schema.provisional.assert().isTrue()
        val backend = queries.binding(type).backend
        backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList().assert().isEmpty()
        backend.countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema)).count.assert().isEqualTo(0L)
        backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema)).total.assert().isEqualTo(0L)
        backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, total = TotalMode.NONE), schema))
            .list.assert().isEmpty()
        backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), schema)).list.assert().isEmpty()
        backend.aggregate(
            QueryAdmission.Trusted.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))), schema),
        ).toList().single().path("count").longValue().assert().isEqualTo(0L)
        // Created since: a query admitted against the provisional schema is refused.
        store.createIndex(type.name)
        runCatching { backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList() }
            .exceptionOrNull().assert().isInstanceOf(QuerySchemaUnavailableException::class.java)
        runCatching { backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema)) }
            .exceptionOrNull().assert().isInstanceOf(QuerySchemaUnavailableException::class.java)
        schema(queries).provisional.assert().isFalse()
    }

    @Test
    fun `a failure other than a missing index fails the query and the load`(): Unit = runBlocking {
        val queries = queries()
        queries.prepare(type)
        store.save(snapshot("a", """{"name":"A"}"""))
        val schema = schema(queries)
        ElasticsearchFixture.client.indices().close { it.index(index) }.get()
        val backend = queries.binding(type).backend
        runCatching { backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList() }
            .exceptionOrNull().assert().isNotNull()
        runCatching { backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema)) }
            .exceptionOrNull().assert().isNotNull()
        runCatching { store.load(type.ref("tenant", "a")) }.exceptionOrNull().assert().isNotNull()
        ElasticsearchFixture.client.indices().open { it.index(index) }.get()
    }

    @Test
    fun `startup prepares the index and maps the state, and a later field reindexes the documents`(): Unit = runBlocking {
        val queries = queries()
        queries.managesIndexes.assert().isTrue()
        queries.prepare(type)
        // A snapshot written before its state was mapped: indexed again once the mapping comes.
        store.save(snapshot("early", """{"name":"Early","amount":1.10,"quantity":1,"big":123456789012345678901234567890}"""))
            .assert().isTrue()
        // A schema compiled without mapping anything (`none`), as the host compiles one before it manages the indexes.
        val report = queries.manageIndexes(type, QueryIndexPlan.of(schema(queries(Ddl.NONE)), emptyList()))
        report.created.assert().contains("state.name", "state.lines", "state.lines.sku")
        queries.manageIndexes(type, QueryIndexPlan.of(schema(queries), emptyList())).assert().isEqualTo(QueryIndexReport.NONE)
        val schema = schema(queries)
        schema.field(QueryField("state.name"))?.binding(QueryCapability.EXACT_MATCH).assert().isNotNull()
        val backend = queries.binding(type).backend
        var found = emptyList<String>()
        repeat(REINDEX_ATTEMPTS) {
            if (found.isEmpty()) {
                ElasticsearchFixture.client.indices().refresh { it.index(index) }.get()
                found = backend.list(
                    QueryAdmission.Trusted.list(ListQuery(EqualFilter(QueryField("state.name"), "Early")), schema),
                ).toList().map { it.path("aggregateId").stringValue() }
                if (found.isEmpty()) delay(REINDEX_WAIT)
            }
        }
        found.assert().containsExactly("early")
        // Reindexing kept the state exactly as it was written.
        ExactJson.of(checkNotNull(store.load(type.ref("tenant", "early"))).state)
            .assert().isEqualTo(ExactJson.of("""{"name":"Early","amount":1.10,"quantity":1,"big":123456789012345678901234567890}"""))
    }

    @Test
    fun `validate reports the missing fields and those mapped otherwise, none touches nothing`(): Unit = runBlocking {
        store.createIndex(type.name)
        ElasticsearchFixture.client.indices().putMapping(
            PutMappingRequest.of {
                it.index(index).withJson(StringReader("""{"properties":{"state":{"properties":{"name":{"type":"long"}}}}}"""))
            },
        ).get()
        val validating = queries(Ddl.VALIDATE)
        validating.prepare(type)
        val report = validating.manageIndexes(type, QueryIndexPlan.of(schema(validating), emptyList()))
        report.created.assert().isEmpty()
        report.missing.assert().contains("state.status")
        report.invalid.assert().containsExactly("state.name")
        val none = queries(Ddl.NONE)
        none.managesIndexes.assert().isFalse()
        none.prepare(type)
        schema(none).field(QueryField("state.status"))?.binding(QueryCapability.EXACT_MATCH).assert().isNull()
        // Auto adds what is missing and keeps the field mapped otherwise.
        queries().manageIndexes(type, QueryIndexPlan.of(schema(queries()), emptyList())).invalid.assert().containsExactly("state.name")
    }

    @Test
    fun `a page reaches only as far as the index's result window`(): Unit = runBlocking {
        val queries = queries()
        queries.prepare(type)
        store.save(snapshot("a", """{"name":"A"}"""))
        val schema = schema(queries)
        schema.storage.paging.maxOffsetWindow.assert().isEqualTo(DEFAULT_MAX_RESULT_WINDOW)
        queries.binding(type).backend.paged(
            QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, pagination = Pagination(1, 1)), schema),
        ).list.size.assert().isEqualTo(1)
    }

    private companion object {
        const val REINDEX_ATTEMPTS = 50
        const val REINDEX_WAIT = 100L
    }
}
