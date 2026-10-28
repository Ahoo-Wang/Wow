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

package me.ahoo.wow.query

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.admission.under
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.PositionedRecord
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.chunks
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.error.QueryBusyException
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.PagingSupport
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.describe
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * Streamed lists as keyset chunks (decision D253): each chunk one `page(Keyset)` under its own permit, every record
 * with its position, resumed strictly after a position, bounded by its own limit only.
 */
class QueryStreamsTest {
    private val storage = InMemoryQueryStorage()
    private val pages = CopyOnWriteArrayList<PageWindow>()

    /** The in-memory backend, recording each window it reads. */
    private val backend: QueryBackend = object : QueryBackend by storage.binding(ManifestFixtures.ORDER).backend {
        private val delegate = storage.binding(ManifestFixtures.ORDER).backend

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
            pages += window
            return delegate.page(query, window)
        }
    }

    private val caller = QueryCaller(entry = QueryEntry.HTTP)
    private val byTotal = listOf(Sort(TOTAL, Sort.Direction.ASC))

    private suspend fun gateway(backend: QueryBackend = this.backend, schema: QueryModelSchema? = null) =
        DefaultQueryGateway(
            ManifestFixtures.ORDER,
            backend,
            FixedQueryModelSchemaProvider(schema ?: ManifestFixtures.orderSchema()),
        )

    private fun save(id: String, total: Int, version: Int = 1) {
        storage.save(
            SnapshotRecord(
                ref = ManifestFixtures.ORDER.ref("tenant", id),
                version = version,
                schemaFingerprint = "test",
                state = """{"buyer":"Alice","total":$total}""",
                scope = Scope("owner"),
                firstEventTime = 1,
                lastEventTime = 1,
            ),
        )
    }

    private fun Flow<PositionedRecord>.ids(): Flow<String> = map { it.record["aggregateId"].asString() }

    @Test
    fun `a stream reads keyset chunks one after the other, each record with its position`(): Unit = runTest {
        (1..5).forEach { save("o-$it", it) }
        val gateway = gateway()
        val streamed = gateway.stream(ListQuery(MatchAllFilter, sort = byTotal), caller, chunkSize = 2).toList()
        streamed.map { it.record["aggregateId"].asString() }.assert()
            .containsExactly("o-1", "o-2", "o-3", "o-4", "o-5")
        pages.map { (it as PageWindow.Keyset).limit }.assert().containsExactly(2, 2, 2)
        pages.first().assert().isEqualTo(PageWindow.Keyset(null, 2))
        // A position is the token a cursor query of the same sort gives for the same record.
        val cursor = gateway.cursor(CursorQuery(MatchAllFilter, sort = byTotal, size = 2), caller)
        cursor.nextCursor.assert().isEqualTo(streamed[1].position)
        // Resumed after the third record, a stream reads strictly after it, and so does a cursor from its position.
        gateway.stream(ListQuery(MatchAllFilter, sort = byTotal), caller, streamed[2].position, 2).ids().toList()
            .assert().containsExactly("o-4", "o-5")
        gateway.cursor(CursorQuery(MatchAllFilter, sort = byTotal, size = 5, cursor = streamed[2].position), caller)
            .list.map { it["aggregateId"].asString() }.assert().containsExactly("o-4", "o-5")
    }

    @Test
    fun `a stream reads at most its own limit, and no budget caps it`(): Unit = runTest {
        (1..5).forEach { save("o-$it", it) }
        val gateway = DefaultQueryGateway(
            ManifestFixtures.ORDER,
            backend,
            FixedQueryModelSchemaProvider(ManifestFixtures.orderSchema()),
            entryPolicy = QueryEntryPolicy(http = QueryBudget("HTTP", maxListSize = 2, maxPageSize = 1)),
        )
        gateway.stream(ListQuery(MatchAllFilter, sort = byTotal, limit = 3), caller, chunkSize = 2).ids().toList()
            .assert().containsExactly("o-1", "o-2", "o-3")
        pages.map { (it as PageWindow.Keyset).limit }.assert().containsExactly(2, 1)
        // No limit: every record, beyond the list cap and the cursor page size.
        gateway.stream(ListQuery(MatchAllFilter, sort = byTotal), caller, chunkSize = 4).ids().toList().assert()
            .hasSize(5)
        // An exact fit ends with one empty page.
        pages.clear()
        gateway.stream(ListQuery(MatchAllFilter, sort = byTotal), caller, chunkSize = 5).toList().assert().hasSize(5)
        pages.assert().hasSize(2)
    }

    @Test
    fun `chunks are not one snapshot - a write after the position read is seen, one before it is not`(): Unit =
        runTest {
            (1..6).forEach { save("o-$it", it * 10) }
            val read = gateway().stream(ListQuery(MatchAllFilter, sort = byTotal), caller, chunkSize = 2)
                .onEach { record ->
                    // Between the first and second chunks.
                    if (record.record["aggregateId"].asString() == "o-2") {
                        save("o-early", 5)
                        save("o-late", 45)
                        save("o-6", 15, version = 2) // moves before the position read: missed
                        save("o-1", 55, version = 2) // moves after it: seen again
                    }
                }.ids().toList()
            read.assert().containsExactly("o-1", "o-2", "o-3", "o-4", "o-late", "o-5", "o-1")
            // Without moves, no record repeats and none goes missing.
            read.filter { it != "o-1" && it != "o-6" }.assert().doesNotHaveDuplicates()
        }

    @Test
    fun `a stream holds no permit between chunks, so a lowered limit takes it back within one chunk`(): Unit =
        runBlocking {
            (1..4).forEach { save("o-$it", it) }
            val bulkhead = QueryBulkhead("main", 1, maxWait = 50.milliseconds)
            val gateway = gateway(backend.under(bulkhead))
            val running = CopyOnWriteArrayList<Int>()
            gateway.stream(ListQuery(MatchAllFilter, sort = byTotal), caller, chunkSize = 2)
                .onEach { running += bulkhead.running }.toList().assert().hasSize(4)
            running.assert().containsOnly(0)
            // Another query takes the one permit while the stream reads its first chunk: the next chunk is refused.
            val holding = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val read = CopyOnWriteArrayList<String>()
            val failure = runCatching {
                gateway.stream(ListQuery(MatchAllFilter, sort = byTotal), caller, chunkSize = 2).ids().collect { id ->
                    read += id
                    if (read.size == 1) {
                        async(Dispatchers.Default) {
                            bulkhead.run {
                                holding.complete(Unit)
                                release.await()
                            }
                        }
                        holding.await()
                    }
                }
            }.exceptionOrNull()
            release.complete(Unit)
            failure.assert().isInstanceOf(QueryBusyException::class.java)
            read.assert().containsExactly("o-1", "o-2")
            while (bulkhead.running > 0) yield()
        }

    @Test
    fun `a position of another sort or model, or no token at all, is the invalid cursor`(): Unit = runTest {
        (1..3).forEach { save("o-$it", it) }
        val gateway = gateway()
        val byId = gateway.stream(ListQuery(MatchAllFilter), caller, chunkSize = 2).toList().first().position
        listOf(byId, "garbage", "").forEach { token ->
            val error = runCatching {
                gateway.stream(ListQuery(MatchAllFilter, sort = byTotal), caller, token, 2).toList()
            }.exceptionOrNull() as QueryRequestException
            error.code.assert().isEqualTo(QueryErrorCodes.INVALID_CURSOR)
        }
    }

    @Test
    fun `a list resumes only where the storage pages by keyset and its sort orders a cursor`(): Unit = runTest {
        val schema = ManifestFixtures.orderSchema()
        val gateway = gateway(schema = schema)
        gateway.resumes(ListQuery(MatchAllFilter)).assert().isTrue()
        gateway.resumes(ListQuery(MatchAllFilter, sort = byTotal)).assert().isTrue()
        // A field the schema does not know is admission's to reject.
        gateway.resumes(ListQuery(MatchAllFilter, sort = listOf(Sort(QueryField("state.nope"), Sort.Direction.ASC))))
            .assert().isTrue()
        // A protected field, an array, the same field twice or too many fields cannot order a cursor.
        listOf("state.password", "state.productIds").forEach { path ->
            schema.field(QueryField(path))!!.effective.cursorSortable.assert().isFalse()
            gateway.resumes(ListQuery(MatchAllFilter, sort = listOf(Sort(QueryField(path), Sort.Direction.ASC))))
                .assert().isFalse()
        }
        val twice = listOf(Sort(TOTAL, Sort.Direction.ASC), Sort(QueryField("state.total"), Sort.Direction.DESC))
        gateway.resumes(ListQuery(MatchAllFilter, sort = twice)).assert().isFalse()
        // The unique key appended to Sort.MAX_FIELDS fields overflows.
        val many = List(Sort.MAX_FIELDS) { Sort(QueryField("state.field$it"), Sort.Direction.ASC) }
        gateway.resumes(ListQuery(MatchAllFilter, sort = many)).assert().isFalse()
        // A storage without keyset pages.
        val noKeyset = QueryModelSchema(
            schema.model,
            schema.capabilities,
            schema.definition,
            schema.bindings,
            storage = StorageSupport(paging = PagingSupport(keyset = SupportMode.NONE)),
        )
        gateway(schema = noKeyset).resumes(ListQuery(MatchAllFilter)).assert().isFalse()
        // The descriptor says so (decision D253).
        noKeyset.describe(null, null).record.stream.resumable.assert().isFalse()
        schema.describe(null, null).record.stream.resumable.assert().isTrue()
        // A gateway that does not stream says so.
        val plain = object : QueryGateway by gateway {
            override suspend fun resumes(query: ListQuery): Boolean =
                super<QueryGateway>.resumes(query)

            override fun stream(
                query: ListQuery,
                caller: QueryCaller,
                after: String?,
                chunkSize: Int,
            ): Flow<PositionedRecord> = super<QueryGateway>.stream(query, caller, after, chunkSize)
        }
        plain.resumes(ListQuery(MatchAllFilter)).assert().isFalse()
        val unsupported = runCatching { plain.stream(ListQuery(MatchAllFilter), caller).toList() }.exceptionOrNull()
        (unsupported as QuerySchemaValidationException).violation!!.code.assert().isEqualTo(
            QueryErrorCodes.STORAGE_UNSUPPORTED
        )
    }

    @Test
    fun `chunks need a non-negative limit and each page's positions`(): Unit = runTest {
        save("o-1", 1)
        val admitted = QueryAdmission.Trusted.cursor(
            CursorQuery(MatchAllFilter, size = 2),
            ManifestFixtures.orderSchema()
        )
        runCatching { backend.chunks(admitted, -1) }.exceptionOrNull().assert()
            .isInstanceOf(IllegalArgumentException::class.java)
        val positionless = object : QueryBackend by backend {
            override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage =
                BackendPage(backend.page(query, window).rows)
        }
        runCatching { positionless.chunks(admitted).toList() }.exceptionOrNull().assert()
            .isInstanceOf(QueryExecutionException::class.java)
        PositionedRecord(WowJson.mapper.createObjectNode(), "p").toString().assert()
            .isEqualTo("PositionedRecord(position=p)")
    }

    private companion object {
        val TOTAL = QueryField("state.total")
    }
}
