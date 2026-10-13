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

package me.ahoo.wow.query.schema

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class QuerySchemaCatalogTest {
    private val order = AggregateType("example", "order")
    private val cart = AggregateType("example", "cart")
    private val exact = setOf(QueryCapability.EXACT_MATCH)
    private val sortable = setOf(QueryCapability.EXACT_MATCH, QueryCapability.SORT)

    private data class Refresh(val status: QuerySchemaCatalog.Status, val changed: Boolean, val elapsed: Duration)

    private class RecordingListener : QuerySchemaRefreshListener {
        val refreshes = CopyOnWriteArrayList<Refresh>()

        override fun refreshed(status: QuerySchemaCatalog.Status, changed: Boolean, elapsed: Duration) {
            refreshes += Refresh(status, changed, elapsed)
        }
    }

    @Test
    fun `revalidation reloads every schema and a failed reload keeps the previous version`() {
        runTest {
            val catalog = QuerySchemaCatalog(
                storages = snapshots { SequenceStorage(exact, sortable, null) },
                aggregates = listOf(order),
            )

            val initial = catalog.versions().single()
            initial.version.assert().isNotNull()
            initial.aggregate.assert().isEqualTo("example.order")
            initial.model.assert().isEqualTo(QueryModel.SNAPSHOT)
            initial.error.assert().isNull()

            val changed = catalog.revalidate().single()
            changed.version.assert().isNotEqualTo(initial.version)

            val failed = catalog.revalidate().single()
            failed.version.assert().isNull()
            failed.error.assert().isEqualTo("storage unreachable")
            catalog.versions().single().version.assert().isEqualTo(changed.version)
        }
    }

    @Test
    fun `the listener sees each revalidation, its failures and version changes`() {
        runTest {
            val listener = RecordingListener()
            val catalog = QuerySchemaCatalog(
                storages = snapshots { SequenceStorage(exact, sortable, sortable, null) },
                aggregates = listOf(order),
                listener = listener,
            )
            catalog.versions()
            repeat(3) { catalog.revalidate() }

            listener.refreshes.map { it.status.error == null }.assert().containsExactly(true, true, false)
            // Only first → second changed the version; second → second and the failed reload did not.
            listener.refreshes.map { it.changed }.assert().containsExactly(true, false, false)
            listener.refreshes.forEach { it.elapsed.isNegative.assert().isFalse() }
        }
    }

    @Test
    fun `a failed reload of a schema never published reports no change`() {
        runTest {
            val listener = RecordingListener()
            val catalog = QuerySchemaCatalog(
                storages = snapshots { SequenceStorage(null) },
                aggregates = listOf(order),
                listener = listener,
            )

            val status = catalog.revalidate().single()

            status.error.assert().isEqualTo("storage unreachable")
            listener.refreshes.single().changed.assert().isFalse()
            catalog.versions().single().error.assert().isEqualTo("storage unreachable")
        }
    }

    @Test
    fun `an error without a message is reported by its type`() {
        runTest {
            val catalog = QuerySchemaCatalog(
                storages = snapshots {
                    object : QueryStorageAdapter {
                        override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
                            throw IllegalStateException()
                    }
                },
                aggregates = listOf(order),
            )

            catalog.versions().single().error.assert().isEqualTo("IllegalStateException")
        }
    }

    @Test
    fun `a revalidation started after the previous one completed reloads again`() {
        runTest {
            val listener = RecordingListener()
            val catalog = QuerySchemaCatalog(
                storages = snapshots { SequenceStorage(exact, sortable, null) },
                aggregates = listOf(order),
                listener = listener,
            )
            catalog.versions()

            val statuses = catalog.revalidate() + catalog.revalidate()

            statuses.map { it.error }.assert().containsExactly(null, "storage unreachable")
            listener.refreshes.assert().hasSize(2)
        }
    }

    @Test
    fun `an aggregate can be selected and models without a backend are skipped`() {
        runTest {
            val catalog = QuerySchemaCatalog(
                storages = QueryStorageLocator { aggregateType, model ->
                    if (model == QueryModel.SNAPSHOT && aggregateType in setOf(order, cart)) {
                        SequenceStorage(exact)
                    } else {
                        UnavailableQueryStorageAdapter("none")
                    }
                },
                aggregates = listOf(order, cart, order),
            )
            catalog.versions().map { it.aggregate to it.model }.assert()
                .containsExactly("example.order" to QueryModel.SNAPSHOT, "example.cart" to QueryModel.SNAPSHOT)
            catalog.versions("example.cart").map { it.aggregate }.assert().containsExactly("example.cart")
            catalog.revalidate("example.cart").map { it.aggregate }.assert().containsExactly("example.cart")
            catalog.revalidate("example.unknown").assert().isEmpty()

            val unavailable = AggregateType("example", "audit")
            assertThrows<QuerySchemaUnavailableException> { catalog.schema(unavailable, QueryModel.SNAPSHOT) }
                .message.assert().isEqualTo("none")
            assertThrows<QuerySchemaUnavailableException> { catalog.schema(order, QueryModel.DATASET) }
                .message.assert().isEqualTo("none")
        }
    }

    @Test
    fun `a catalog without backends serves no model`() {
        runTest {
            val catalog = QuerySchemaCatalog(aggregates = listOf(order))

            catalog.versions().assert().isEmpty()
            catalog.revalidate().assert().isEmpty()
            assertThrows<QuerySchemaUnavailableException> { catalog.schema(order, QueryModel.SNAPSHOT) }
                .message.assert().isEqualTo("No query backend is configured for aggregate[example.order].")
            QueryStorageLocator.NONE.storage(order, QueryModel.SNAPSHOT).assert().isNull()
            QuerySchemaRefreshListener.NONE.refreshed(
                QuerySchemaCatalog.Status("example.order", QueryModel.SNAPSHOT),
                changed = false,
                elapsed = Duration.ZERO,
            )
        }
    }

    @Test
    fun `a model's revalidation reloads only that model and concurrent ones share the reload in flight`() {
        runTest {
            val reloads = AtomicInteger()
            val pending = CompletableDeferred<QueryModelSchema>()
            val catalog = QuerySchemaCatalog(
                storages = snapshots { SequenceStorage(exact) },
                compiler = { _, _ ->
                    object : QueryModelSchemaProvider {
                        override suspend fun schema(): QueryModelSchema = throw IllegalStateException("not loaded")

                        override suspend fun refresh(): QueryModelSchema {
                            reloads.incrementAndGet()
                            return pending.await()
                        }
                    }
                },
                aggregates = listOf(order),
            )

            val first = async { catalog.revalidate("example.order", QueryModel.SNAPSHOT) }
            val second = async { catalog.revalidate("example.order", QueryModel.SNAPSHOT) }
            runCurrent()
            pending.completeExceptionally(IllegalStateException("reloaded"))

            first.await().map { it.model to it.error }.assert().containsExactly(QueryModel.SNAPSHOT to "reloaded")
            second.await().map { it.model to it.error }.assert().containsExactly(QueryModel.SNAPSHOT to "reloaded")
            reloads.get().assert().isOne()
            catalog.revalidate("example.order", QueryModel.DATASET).assert().isEmpty()
            reloads.get().assert().isOne()
        }
    }

    @Test
    fun `a revalidation that fails outside a schema load reaches every caller sharing it`() {
        runTest {
            val pending = CompletableDeferred<Unit>()
            val failure = IllegalStateException("listener failed")
            val catalog = QuerySchemaCatalog(
                storages = snapshots { SequenceStorage(exact) },
                aggregates = listOf(order),
                listener = { _, _, _ -> throw failure },
                compiler = { _, _ ->
                    object : QueryModelSchemaProvider {
                        override suspend fun schema(): QueryModelSchema = throw IllegalStateException("not loaded")

                        override suspend fun refresh(): QueryModelSchema {
                            pending.await()
                            throw IllegalStateException("reloaded")
                        }
                    }
                },
            )

            val first = async { runCatching { catalog.revalidate() } }
            val second = async { runCatching { catalog.revalidate() } }
            runCurrent()
            pending.complete(Unit)

            listOf(first.await(), second.await()).forEach { result ->
                result.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
                    .hasMessage(failure.message)
            }
        }
    }

    @Test
    fun `a cancelled revalidation does not fail the revalidations sharing it`() {
        runTest {
            val pending = CompletableDeferred<QueryModelSchema>()
            val reloads = AtomicInteger()
            val schema = boundSchemaFixture(objectFixture())
            val catalog = QuerySchemaCatalog(
                storages = snapshots { SequenceStorage(exact) },
                compiler = { _, _ ->
                    object : QueryModelSchemaProvider {
                        override suspend fun schema(): QueryModelSchema = schema

                        override suspend fun refresh(): QueryModelSchema =
                            if (reloads.getAndIncrement() == 0) pending.await() else schema
                    }
                },
                aggregates = listOf(order),
            )

            val cancelled = async { catalog.revalidate() }
            runCurrent()
            val shared = async { catalog.revalidate() }
            runCurrent()
            cancelled.cancel()

            shared.await().single().version.assert().isEqualTo(schema.version)
            reloads.get().assert().isEqualTo(2)
        }
    }

    @Test
    fun `the catalog compiles each model once, from its sources and storage facts`() {
        runTest {
            val storage = SequenceStorage(exact)
            val source = BeanQuerySchemaSource(
                listOf(
                    querySchemaRegistration(order, QueryModel.SNAPSHOT) {
                        field("state.name") { types(QueryValueType.STRING) }
                    },
                ),
            )
            val catalog = QuerySchemaCatalog(snapshots { storage }, QueryModelCompiler.of(listOf(source)))
            val provider = catalog.provider(order, QueryModel.SNAPSHOT)
            catalog.provider(order, QueryModel.SNAPSHOT).assert().isSameAs(provider)
            val schema = catalog.schema(order, QueryModel.SNAPSHOT)
            schema.model.assert().isEqualTo(QueryModel.SNAPSHOT)
            schema.field(QueryField("aggregateId"))!!.capabilities.assert().isEqualTo(exact)
            schema.field(QueryField("state.name"))!!.capabilities.assert().isEqualTo(exact)
            catalog.schema(order, QueryModel.SNAPSHOT).assert().isSameAs(schema)
            storage.loads.get().assert().isOne()
            // The default compiler merges no declarations beyond the system fields.
            QuerySchemaCatalog(snapshots { SequenceStorage(exact) }).schema(cart, QueryModel.SNAPSHOT)
                .field(QueryField("state.name")).assert().isNull()
        }
    }

    private fun snapshots(storage: () -> QueryStorageAdapter) = QueryStorageLocator { _, model ->
        if (model == QueryModel.SNAPSHOT) storage() else null
    }

    /**
     * Reports the capabilities of [sequence] in turn, every path granted them: the first on [facts], later ones on
     * [refresh]; `null` fails the reload.
     */
    private class SequenceStorage(private vararg val sequence: Set<QueryCapability>?) : QueryStorageAdapter {
        val loads = AtomicInteger()

        override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts = refresh(logicalSchema)

        override suspend fun refresh(logicalSchema: LogicalQuerySchema): QueryStorageFacts {
            val next = sequence.getOrNull(loads.getAndIncrement().coerceAtMost(sequence.size - 1))
                ?: throw IllegalStateException("storage unreachable")
            return QueryStorageFacts(
                logicalSchema.values.keys.filter { it.segments.isNotEmpty() }.associateWith { path ->
                    QueryValueBindings(next.associateWith { QueryFieldBindingTemplate(path, null) }, path, path)
                },
            )
        }
    }
}
