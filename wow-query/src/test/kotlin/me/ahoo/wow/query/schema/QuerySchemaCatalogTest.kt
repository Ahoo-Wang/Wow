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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.event.AbstractEventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.tck.query.NoOpEventStreamQueryBackend
import me.ahoo.wow.tck.query.NoOpSnapshotQueryBackend
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.atomic.AtomicInteger

class QuerySchemaCatalogTest {
    private val order = MaterializedNamedAggregate("example", "order")
    private val cart = MaterializedNamedAggregate("example", "cart")
    private val exact = setOf(QueryCapability.EXACT_MATCH)
    private val sortable = setOf(QueryCapability.EXACT_MATCH, QueryCapability.SORT)

    @Test
    fun `revalidation reloads every schema and a failed reload keeps the previous version`() {
        val catalog = QuerySchemaCatalog(
            snapshots = snapshots { SequenceStorage(exact, sortable, null) },
            aggregates = listOf(order),
        )

        val initial = catalog.versions().blockFirst()!!
        initial.version.assert().isNotNull()
        initial.aggregate.assert().isEqualTo("example.order")

        val changed = catalog.revalidate().blockFirst()!!
        changed.version.assert().isNotEqualTo(initial.version)

        catalog.revalidate().test().assertNext {
            it.version.assert().isNull()
            it.error.assert().isEqualTo("storage unreachable")
        }.verifyComplete()
        catalog.versions().blockFirst()!!.version.assert().isEqualTo(changed.version)
    }

    @Test
    fun `revalidation publishes refresh timing, failures and version changes`() {
        val registry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        val catalog = QuerySchemaCatalog(
            snapshots = snapshots { SequenceStorage(exact, sortable, sortable, null) },
            aggregates = listOf(order),
            meterRegistry = registry,
        )
        catalog.versions().blockLast()
        repeat(3) { catalog.revalidate().blockLast() }

        fun timer(outcome: String) = registry.find(QuerySchemaCatalog.SCHEMA_REFRESH)
            .tags("context", "example", "aggregate", "order", "model", "snapshot", "outcome", outcome).timer()
        timer("success")!!.count().assert().isEqualTo(2)
        timer("failure")!!.count().assert().isEqualTo(1)
        // Only first → second changed the version; second → second and the failed reload did not.
        registry.find(QuerySchemaCatalog.SCHEMA_VERSION_CHANGES).counter()!!.count().assert().isEqualTo(1.0)
    }

    @Test
    fun `an aggregate can be selected and models without a backend are skipped`() {
        val catalog = QuerySchemaCatalog(
            snapshots = snapshots { SequenceStorage(exact) },
            eventStreams = object : AbstractEventStreamQueryBackendFactory() {
                override fun createBinding(
                    namedAggregate: NamedAggregate
                ): QueryBackendBinding<EventStreamQueryBackend> =
                    QueryBackendBinding(
                        NoOpEventStreamQueryBackend(namedAggregate),
                        UnavailableQueryStorageAdapter("none")
                    )
            },
            aggregates = listOf(order, cart),
        )
        catalog.versions().map { it.aggregate to it.model }.collectList().block()!!.assert()
            .containsExactly("example.order" to QueryModel.SNAPSHOT, "example.cart" to QueryModel.SNAPSHOT)
        catalog.revalidate("example.cart").map { it.aggregate }.collectList().block()!!.assert()
            .containsExactly("example.cart")
        catalog.schema(cart, QueryModel.EVENT_STREAM).test().expectError(QuerySchemaUnavailableException::class.java)
            .verify()
        QuerySchemaCatalog(aggregates = listOf(order)).versions().collectList().block()!!.assert().isEmpty()
    }

    @Test
    fun `the catalog compiles each model once, from its sources and storage facts`() {
        val storage = SequenceStorage(exact)
        val catalog = QuerySchemaCatalog(snapshots = snapshots { storage })
        val provider = catalog.provider(order, QueryModel.SNAPSHOT)
        catalog.provider(order, QueryModel.SNAPSHOT).assert().isSameAs(provider)
        val schema = catalog.schema(order, QueryModel.SNAPSHOT).block()!!
        schema.model.assert().isEqualTo(QueryModel.SNAPSHOT)
        schema.field(me.ahoo.wow.api.query.QueryField("aggregateId"))!!.capabilities.assert().isEqualTo(exact)
        storage.loads.get().assert().isOne()
    }

    private fun snapshots(storage: () -> QueryStorageAdapter) = object : AbstractSnapshotQueryBackendFactory() {
        override fun createBinding(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> =
            QueryBackendBinding(NoOpSnapshotQueryBackend(namedAggregate), storage())
    }

    /**
     * Reports [loads] in turn, every path granted those capabilities: the first on [facts], later ones on [refresh];
     * `null` fails the reload.
     */
    private class SequenceStorage(private vararg val sequence: Set<QueryCapability>?) : QueryStorageAdapter {
        val loads = AtomicInteger()

        override fun facts(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> = refresh(logicalSchema)

        override fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> {
            val next = sequence.getOrNull(loads.getAndIncrement().coerceAtMost(sequence.size - 1))
                ?: return Mono.error(IllegalStateException("storage unreachable"))
            return Mono.just(
                QueryStorageFacts(
                    logicalSchema.values.keys.filter { it.segments.isNotEmpty() }.associateWith { path ->
                        QueryValueBindings(next.associateWith { QueryFieldBindingTemplate(path, null) }, path, path)
                    },
                ),
            )
        }
    }
}
