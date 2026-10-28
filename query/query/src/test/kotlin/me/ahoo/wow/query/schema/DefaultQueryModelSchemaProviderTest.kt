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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DefaultQueryModelSchemaProviderTest {
    @Test
    fun `concurrent first callers should share one load and immutable schema`() {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = CountingSource()
            val adapter = CountingAdapter(beforeFacts = { gate.await() })
            val provider = provider(source, adapter)

            val first = async { provider.schema() }
            val second = async { provider.schema() }
            runCurrent()
            gate.complete(Unit)

            first.await().assert().isSameAs(second.await())
            provider.schema().assert().isSameAs(first.await())
            source.loads.get().assert().isEqualTo(1)
            adapter.resolves.get().assert().isEqualTo(1)
        }
    }

    @Test
    fun `a schema a refresh published is answered without a first load`() {
        runTest {
            val source = CountingSource()
            val adapter = CountingAdapter()
            val provider = provider(source, adapter)

            val refreshed = provider.refresh()

            provider.schema().assert().isSameAs(refreshed)
            source.loads.get().assert().isEqualTo(0)
            adapter.resolves.get().assert().isEqualTo(0)
        }
    }

    @Test
    fun `refresh should remain published when an earlier first load completes later`() {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = CountingSource()
            val adapter = CountingAdapter(beforeFacts = { gate.await() })
            val provider = provider(source, adapter)

            val initial = async { provider.schema() }
            runCurrent()
            val refreshed = provider.refresh()
            gate.complete(Unit)

            initial.await().assert().isNotSameAs(refreshed)
            provider.schema().assert().isSameAs(refreshed)
        }
    }

    @Test
    fun `successful refresh should publish the new immutable schema`() {
        runTest {
            val source = CountingSource()
            val adapter = CountingAdapter(
                refreshedFacts = { QueryStorageFacts(emptyMap(), fullProjectionAvailable = false) }
            )
            val provider = provider(source, adapter)
            val initial = provider.schema()

            val refreshed = provider.refresh()

            refreshed.assert().isNotSameAs(initial)
            refreshed.fullProjectionAvailable.assert().isFalse()
            refreshed.version.assert().isNotEqualTo(initial.version)
            provider.schema().assert().isSameAs(refreshed)
            source.refreshes.get().assert().isEqualTo(1)
            adapter.refreshes.get().assert().isEqualTo(1)
        }
    }

    @Test
    fun `refresh with unchanged facts keeps the instance`() {
        runTest {
            val source = CountingSource()
            val adapter = CountingAdapter()
            val provider = provider(source, adapter)
            val initial = provider.schema()
            val field = initial.field(QueryField("aggregateId"))

            val refreshed = provider.refresh()

            refreshed.assert().isSameAs(initial)
            provider.schema().assert().isSameAs(initial)
            // The kept instance keeps its compiled fields.
            refreshed.field(QueryField("aggregateId")).assert().isSameAs(field)
            source.refreshes.get().assert().isEqualTo(1)
            adapter.refreshes.get().assert().isEqualTo(1)
        }
    }

    @Test
    fun `refresh with changed bindings publishes a new instance`() {
        runTest {
            val path = QueryField("aggregateId").toPathTemplate()
            fun facts(physical: String) = QueryStorageFacts(
                mapOf(
                    path to QueryValueBindings(
                        mapOf(
                            QueryCapability.EXACT_MATCH to
                                QueryFieldBindingTemplate(QueryField(physical).toPathTemplate(), null)
                        )
                    )
                )
            )
            val adapter = object : QueryStorageAdapter {
                override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts = facts("aggregateId")

                override suspend fun refresh(logicalSchema: LogicalQuerySchema): QueryStorageFacts = facts("_id")
            }
            val provider = provider(CountingSource(), adapter)
            val initial = provider.schema()

            val refreshed = provider.refresh()

            refreshed.assert().isNotSameAs(initial)
            refreshed.field(QueryField("aggregateId"))!!.bindings.values.single().physicalField.assert()
                .isEqualTo(QueryField("_id"))
        }
    }

    @Test
    fun `refresh with a changed logical definition publishes a new instance`() {
        runTest {
            val titled = AtomicBoolean()
            val source = object : QuerySchemaSource {
                override val priority: Int = QuerySchemaSourcePriority.BEAN

                override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> = listOf(
                    querySchema {
                        field("state.name") {
                            types(QueryValueType.STRING)
                            if (titled.get()) title("Name")
                        }
                    },
                )
            }
            val provider = DefaultQueryModelSchemaProvider(CONTEXT, listOf(source), CountingAdapter())
            val initial = provider.schema()
            titled.set(true)

            val refreshed = provider.refresh()

            refreshed.assert().isNotSameAs(initial)
            refreshed.root.properties.getValue("state").properties.getValue("name").title.assert().isEqualTo("Name")
        }
    }

    @Test
    fun `concurrent refresh callers should share one generation`() {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = CountingSource(beforeRefresh = { gate.await() })
            val adapter = CountingAdapter()
            val provider = provider(source, adapter)

            val first = async { provider.refresh() }
            val second = async { provider.refresh() }
            runCurrent()
            gate.complete(Unit)

            first.await().assert().isSameAs(second.await())
            source.refreshes.get().assert().isEqualTo(1)
            adapter.refreshes.get().assert().isEqualTo(1)
        }
    }

    @Test
    fun `completed refresh should allow a new generation`() {
        runTest {
            val source = CountingSource()
            val adapter = CountingAdapter()
            val provider = provider(source, adapter)

            val first = provider.refresh()
            val second = provider.refresh()

            // Each refresh reloads the sources and the storage; the facts are unchanged, so the instance is kept.
            second.assert().isSameAs(first)
            source.refreshes.get().assert().isEqualTo(2)
            adapter.refreshes.get().assert().isEqualTo(2)
        }
    }

    @Test
    fun `a cancelled refresh caller does not fail the callers sharing its generation`() {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val generation = AtomicInteger()
            val source = CountingSource(beforeRefresh = { if (generation.getAndIncrement() == 0) gate.await() })
            val adapter = CountingAdapter()
            val provider = provider(source, adapter)

            val cancelled = async { provider.refresh() }
            runCurrent()
            val shared = async { provider.refresh() }
            runCurrent()
            cancelled.cancel()

            shared.await().assert().isNotNull()
            source.refreshes.get().assert().isEqualTo(2)
            adapter.refreshes.get().assert().isEqualTo(1)
        }
    }

    @Test
    fun `failed refresh should preserve the previous schema and allow retry`() {
        runTest {
            val source = CountingSource()
            val adapter = CountingAdapter()
            val provider = provider(source, adapter)
            val initial = provider.schema()
            adapter.failRefresh.set(true)

            assertThrows<QuerySchemaUnavailableException> { provider.refresh() }.assert()
                .isSameAs(adapter.refreshFailure)

            provider.schema().assert().isSameAs(initial)
            adapter.failRefresh.set(false)
            val refreshed = provider.refresh()
            provider.schema().assert().isSameAs(refreshed)
            adapter.refreshes.get().assert().isEqualTo(2)
        }
    }

    @Test
    fun `failed first load should clear in-flight state and allow retry`() {
        runTest {
            val source = CountingSource(failLoads = AtomicInteger(1))
            val provider = provider(source, CountingAdapter())

            assertThrows<QuerySchemaUnavailableException> { provider.schema() }

            provider.schema().assert().isNotNull()
            source.loads.get().assert().isEqualTo(2)
        }
    }

    @Test
    fun `a storage that cannot be reached is unavailable, not a broken schema, and the next load asks again`() {
        runTest {
            // Finding F23: a backend wraps the connection failure of its schema read; the cause says the store is down.
            val down = AtomicBoolean(true)
            val adapter = object : QueryStorageAdapter {
                override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts {
                    if (down.get()) {
                        throw QuerySchemaUnavailableException(
                            "Failed to resolve the schema.",
                            StorageUnavailableException("The store is unavailable; retry later."),
                        )
                    }
                    return newFacts()
                }

                override suspend fun refresh(logicalSchema: LogicalQuerySchema): QueryStorageFacts = facts(
                    logicalSchema
                )
            }
            val provider = provider(CountingSource(), adapter)
            val failure = assertThrows<QueryStorageUnavailableException> { provider.schema() }
            failure.errorCode.assert().isEqualTo(QueryErrors.STORAGE_UNAVAILABLE)
            assertThrows<QueryStorageUnavailableException> { provider.refresh() }
            down.set(false)
            provider.schema().assert().isNotNull()
        }
    }

    @Test
    fun `a schema read that is cancelled stays cancelled`() {
        runTest {
            val adapter = object : QueryStorageAdapter {
                override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
                    throw CancellationException("The caller went away.")

                override suspend fun refresh(logicalSchema: LogicalQuerySchema): QueryStorageFacts = facts(
                    logicalSchema
                )
            }
            assertThrows<CancellationException> { provider(CountingSource(), adapter).schema() }
        }
    }

    @Test
    fun `a schema read that fails for another reason stays the schema's failure`() {
        runTest {
            val adapter = CountingAdapter().also { it.failFacts.set(true) }
            assertThrows<QuerySchemaUnavailableException> { provider(CountingSource(), adapter).schema() }
        }
    }

    @Test
    fun `callers sharing a failed first load fail with it`() {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val adapter = CountingAdapter(beforeFacts = { gate.await() })
            adapter.failFacts.set(true)
            val provider = provider(CountingSource(), adapter)

            val first = async { runCatching { provider.schema() } }
            val second = async { runCatching { provider.schema() } }
            runCurrent()
            gate.complete(Unit)

            listOf(first.await(), second.await()).forEach { result ->
                result.exceptionOrNull().assert().isInstanceOf(QuerySchemaUnavailableException::class.java)
                    .hasMessage(adapter.factsFailure.message)
            }
            adapter.resolves.get().assert().isEqualTo(1)
        }
    }

    @Test
    fun `provider instances should never share final cache state`() {
        runTest {
            val source = CountingSource()
            val adapter = CountingAdapter()
            val first = provider(source, adapter).schema()
            val second = provider(source, adapter).schema()

            first.assert().isNotSameAs(second)
            source.loads.get().assert().isEqualTo(2)
        }
    }

    @Test
    fun `a model without a profile is unsupported`() {
        runTest {
            val provider = DefaultQueryModelSchemaProvider(
                QuerySchemaContext(AggregateType("test-context", "test-aggregate"), QueryModel("custom")),
                listOf(CountingSource()),
                CountingAdapter(),
            )

            assertThrows<QuerySchemaValidationException> { provider.schema() }
        }
    }

    @Test
    fun `the logical schema the adapter compiles merges the system fields and the sources`() {
        runTest {
            val compiled = mutableListOf<LogicalQuerySchema>()
            val adapter = object : QueryStorageAdapter {
                override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts {
                    compiled += logicalSchema
                    return newFacts()
                }
            }
            val source = BeanQuerySchemaSource(
                listOf(
                    querySchemaRegistration(CONTEXT.aggregateType, QueryModel.SNAPSHOT) {
                        field("state.name") { types(QueryValueType.STRING) }
                    },
                ),
            )
            val schema = DefaultQueryModelSchemaProvider(CONTEXT, listOf(source), adapter).schema()
            // The default adapter refresh reads the current facts again.
            DefaultQueryModelSchemaProvider(CONTEXT, listOf(source), adapter).refresh()

            compiled.assert().hasSize(2)
            compiled.first().value(QueryField("state.name").toPathTemplate()).assert().isNotNull()
            compiled.first().value(QueryField("tenantId").toPathTemplate()).assert().isNotNull()
            schema.definition.assert().isSameAs(compiled.first())
            schema.profile.assert().isSameAs(SnapshotQueryModelProfile)
        }
    }

    @Test
    fun `a provisional schema is kept for its ttl, never published, and the storage is compiled again after it`() {
        runTest {
            val provisional = AtomicBoolean(true)
            val resolves = AtomicInteger()
            val now = AtomicLong()
            val adapter = object : QueryStorageAdapter {
                override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts {
                    resolves.incrementAndGet()
                    return QueryStorageFacts(bindings = emptyMap(), provisional = provisional.get())
                }
            }
            val ttl = Duration.ofSeconds(2)
            val provider = DefaultQueryModelSchemaProvider(
                context = CONTEXT,
                sources = listOf(CountingSource()),
                adapter = adapter,
                provisionalTtl = ttl,
                nanoTime = now::get,
            )

            val first = provider.schema()
            first.provisional.assert().isTrue()
            provider.schema().assert().isSameAs(first)
            resolves.get().assert().isEqualTo(1)

            now.addAndGet(ttl.toNanos())
            provider.schema().assert().isNotSameAs(first)
            resolves.get().assert().isEqualTo(2)

            provider.refresh().provisional.assert().isTrue()
            provider.schema()
            resolves.get().assert().isEqualTo(3)

            // The storage now exists: once the provisional schema expires, its own schema is published.
            provisional.set(false)
            provider.schema().provisional.assert().isTrue()
            now.addAndGet(ttl.toNanos())
            val existing = provider.schema()
            existing.provisional.assert().isFalse()
            now.addAndGet(ttl.toNanos())
            provider.schema().assert().isSameAs(existing)
            resolves.get().assert().isEqualTo(4)

            // A refresh that finds the storage gone again answers provisionally and unpublishes the schema.
            provisional.set(true)
            provider.refresh().provisional.assert().isTrue()
            provider.schema().provisional.assert().isTrue()
            DefaultQueryModelSchemaProvider.DEFAULT_PROVISIONAL_TTL.assert().isEqualTo(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `a fixed provider answers its schema on every load and refresh`() {
        runTest {
            val schema = boundSchemaFixture(objectFixture("name" to scalarFixture()))
            val provider = FixedQueryModelSchemaProvider(schema)

            provider.schema().assert().isSameAs(schema)
            provider.refresh().assert().isSameAs(schema)
        }
    }

    @Test
    fun `an unavailable provider fails every load with its message`() {
        runTest {
            val provider = UnavailableQueryModelSchemaProvider("No backend.")

            assertThrows<QuerySchemaUnavailableException> { provider.schema() }.message.assert().isEqualTo(
                "No backend."
            )
            assertThrows<QuerySchemaUnavailableException> { provider.refresh() }
        }
    }

    private fun provider(
        source: QuerySchemaSource,
        adapter: QueryStorageAdapter,
    ) = DefaultQueryModelSchemaProvider(
        context = CONTEXT,
        sources = listOf(source),
        adapter = adapter,
    )

    private class CountingSource(
        private val failLoads: AtomicInteger = AtomicInteger(),
        private val beforeRefresh: suspend () -> Unit = {},
    ) : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.MANIFEST
        val loads = AtomicInteger()
        val refreshes = AtomicInteger()

        override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> {
            loads.incrementAndGet()
            if (failLoads.getAndUpdate { failures -> (failures - 1).coerceAtLeast(0) } > 0) {
                throw QuerySchemaUnavailableException("Load failed.")
            }
            return listOf(QuerySchemaDeclaration(emptyMap()))
        }

        override suspend fun refresh(context: QuerySchemaContext): List<QuerySchemaDeclaration> {
            refreshes.incrementAndGet()
            beforeRefresh()
            return listOf(QuerySchemaDeclaration(emptyMap()))
        }
    }

    private class CountingAdapter(
        private val refreshedFacts: () -> QueryStorageFacts = ::newFacts,
        private val beforeFacts: suspend () -> Unit = {},
    ) : QueryStorageAdapter {
        val resolves = AtomicInteger()
        val refreshes = AtomicInteger()
        val failFacts = AtomicBoolean()
        val failRefresh = AtomicBoolean()
        val factsFailure = QuerySchemaUnavailableException("Load failed.")
        val refreshFailure = QuerySchemaUnavailableException("Refresh failed.")

        override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts {
            resolves.incrementAndGet()
            beforeFacts()
            if (failFacts.get()) throw factsFailure
            return newFacts()
        }

        override suspend fun refresh(logicalSchema: LogicalQuerySchema): QueryStorageFacts {
            refreshes.incrementAndGet()
            if (failRefresh.get()) throw refreshFailure
            return refreshedFacts()
        }
    }

    companion object {
        private val CONTEXT = QuerySchemaContext(
            AggregateType("test-context", "test-aggregate"),
            QueryModel.SNAPSHOT,
        )

        private fun newFacts() = QueryStorageFacts(emptyMap())
    }
}
