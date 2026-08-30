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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.requiredQueryModelSchemaProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DefaultQueryModelSchemaProviderTest {
    @Test
    fun `concurrent first subscribers should share one load and immutable schema`() {
        val source = CountingSource()
        val adapter = CountingAdapter()
        val provider = provider(source, adapter)

        StepVerifier.create(Mono.zip(provider.schema(), provider.schema()))
            .assertNext { pair -> pair.t1.assert().isSameAs(pair.t2) }
            .verifyComplete()

        source.loads.get().assert().isEqualTo(1)
        adapter.resolves.get().assert().isEqualTo(1)
    }

    @Test
    fun `stale first load mono should reuse schema published before its subscription`() {
        val source = CountingSource()
        val adapter = FixedAdapter()
        val provider = provider(source, adapter)
        val staleFirstLoad = provider.schema()

        val refreshed = provider.refresh().block()!!
        val staleResult = staleFirstLoad.block()!!

        staleResult.assert().isSameAs(refreshed)
        provider.schema().block()!!.assert().isSameAs(refreshed)
        source.loads.get().assert().isEqualTo(0)
        adapter.resolves.get().assert().isEqualTo(0)
    }

    @Test
    fun `refresh should remain published when an earlier first load completes later`() {
        val source = CountingSource()
        val adapter = DelayedResolveAdapter()
        val provider = provider(source, adapter)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val initialFuture = CompletableFuture.supplyAsync({ provider.schema().block()!! }, executor)
            adapter.resolveSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

            val refreshed = provider.refresh().block()!!
            adapter.initialResult.tryEmitValue(adapter.initial).assert().isEqualTo(Sinks.EmitResult.OK)

            initialFuture.get(1, TimeUnit.SECONDS).assert().isSameAs(adapter.initial)
            provider.schema().block()!!.assert().isSameAs(refreshed)
        } finally {
            adapter.initialResult.tryEmitValue(adapter.initial)
            executor.shutdownNow()
        }
    }

    @Test
    fun `successful refresh should publish the new immutable schema`() {
        val source = CountingSource()
        val adapter = CountingAdapter()
        val provider = provider(source, adapter)
        val initial = provider.schema().block()!!

        val refreshed = provider.refresh().block()!!

        refreshed.assert().isNotSameAs(initial)
        provider.schema().block()!!.assert().isSameAs(refreshed)
        source.refreshes.get().assert().isEqualTo(1)
        adapter.refreshes.get().assert().isEqualTo(1)
    }

    @Test
    fun `concurrent refresh subscribers should share one generation`() {
        val result = Sinks.one<QuerySchemaDeclaration>()
        val source = CountingSource(refreshedDeclarations = { result.asMono().flux() })
        val adapter = CountingAdapter()
        val provider = provider(source, adapter)

        val first = provider.refresh().toFuture()
        val second = provider.refresh().toFuture()
        result.tryEmitValue(QuerySchemaDeclaration(emptyMap())).assert().isEqualTo(Sinks.EmitResult.OK)

        first.get(1, TimeUnit.SECONDS).assert().isSameAs(second.get(1, TimeUnit.SECONDS))
        source.refreshes.get().assert().isEqualTo(1)
        adapter.refreshes.get().assert().isEqualTo(1)
    }

    @Test
    fun `completed refresh should allow a new generation`() {
        val source = CountingSource()
        val adapter = CountingAdapter()
        val provider = provider(source, adapter)

        val first = provider.refresh().block()!!
        val second = provider.refresh().block()!!

        second.assert().isNotSameAs(first)
        source.refreshes.get().assert().isEqualTo(2)
        adapter.refreshes.get().assert().isEqualTo(2)
    }

    @Test
    fun `cancelled refresh should retain one generation until it terminates`() {
        val cancelledResult = Sinks.one<QuerySchemaDeclaration>()
        val resumedResult = Sinks.one<QuerySchemaDeclaration>()
        val generation = AtomicInteger()
        val source = CountingSource(
            refreshedDeclarations = {
                when (generation.getAndIncrement()) {
                    0 -> cancelledResult.asMono().flux()
                    1 -> resumedResult.asMono().flux()
                    else -> Flux.just(QuerySchemaDeclaration(emptyMap()))
                }
            },
        )
        val adapter = CountingAdapter()
        val provider = provider(source, adapter)

        val refresh = provider.refresh()
        refresh.subscribe().dispose()
        val resumed = provider.refresh()
        resumed.assert().isSameAs(refresh)

        val first = refresh.toFuture()
        val second = resumed.toFuture()
        resumedResult.tryEmitValue(QuerySchemaDeclaration(emptyMap())).assert().isEqualTo(Sinks.EmitResult.OK)
        val shared = first.get(1, TimeUnit.SECONDS)
        shared.assert().isSameAs(second.get(1, TimeUnit.SECONDS))

        source.refreshes.get().assert().isEqualTo(2)
        adapter.refreshes.get().assert().isEqualTo(1)

        provider.refresh().block()!!.assert().isNotSameAs(shared)
        source.refreshes.get().assert().isEqualTo(3)
        adapter.refreshes.get().assert().isEqualTo(2)
    }

    @Test
    fun `failed refresh should preserve the previous schema and allow retry`() {
        val source = CountingSource()
        val adapter = CountingAdapter()
        val provider = provider(source, adapter)
        val initial = provider.schema().block()!!
        adapter.failRefresh.set(true)

        StepVerifier.create(provider.refresh())
            .expectErrorSatisfies { error -> error.assert().isSameAs(adapter.refreshFailure) }
            .verify()

        provider.schema().block()!!.assert().isSameAs(initial)
        adapter.failRefresh.set(false)
        val refreshed = provider.refresh().block()!!
        provider.schema().block()!!.assert().isSameAs(refreshed)
        adapter.refreshes.get().assert().isEqualTo(2)
    }

    @Test
    fun `failed first load should clear inflight state and allow retry`() {
        val source = CountingSource(failLoads = AtomicInteger(1))
        val provider = provider(source, CountingAdapter())

        StepVerifier.create(provider.schema())
            .expectError(QuerySchemaUnavailableException::class.java)
            .verify()

        provider.schema().block().assert().isNotNull()
        source.loads.get().assert().isEqualTo(2)
    }

    @Test
    fun `immediate chained recovery should start a fresh first load after cached error`() {
        val source = CountingSource(failLoads = AtomicInteger(1))
        val provider = provider(source, CountingAdapter())

        StepVerifier.create(provider.schema().onErrorResume { provider.schema() })
            .expectNextCount(1)
            .verifyComplete()

        source.loads.get().assert().isEqualTo(2)
    }

    @Test
    fun `provider instances should never share final cache state`() {
        val source = CountingSource()
        val adapter = CountingAdapter()
        val first = provider(source, adapter).schema().block()!!
        val second = provider(source, adapter).schema().block()!!

        first.assert().isNotSameAs(second)
        source.loads.get().assert().isEqualTo(2)
    }

    @Test
    fun `required provider should return capable backend and reject incapable backend`() {
        val capable = object :
            SnapshotQueryBackend by NoOpSnapshotQueryBackend(CONTEXT.namedAggregate),
            QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(newSchema())

            override fun refresh(): Mono<QueryModelSchema> = schema()
        }

        capable.requiredQueryModelSchemaProvider().assert().isSameAs(capable)
        assertThrows<QuerySchemaUnavailableException> {
            NoOpSnapshotQueryBackend(CONTEXT.namedAggregate).requiredQueryModelSchemaProvider()
        }
    }

    private fun provider(
        source: QuerySchemaSource,
        adapter: QuerySchemaBackendAdapter,
    ) = DefaultQueryModelSchemaProvider(
        context = CONTEXT,
        sources = listOf(source),
        adapter = adapter,
    )

    private class CountingSource(
        private val failLoads: AtomicInteger = AtomicInteger(),
        private val refreshedDeclarations: () -> Flux<QuerySchemaDeclaration> = {
            Flux.just(QuerySchemaDeclaration(emptyMap()))
        },
    ) : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.JSON_SCHEMA
        val loads = AtomicInteger()
        val refreshes = AtomicInteger()

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.defer {
            loads.incrementAndGet()
            if (failLoads.getAndUpdate { failures -> (failures - 1).coerceAtLeast(0) } > 0) {
                Flux.error(QuerySchemaUnavailableException("Load failed."))
            } else {
                Flux.just(QuerySchemaDeclaration(emptyMap()))
            }
        }

        override fun refresh(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.defer {
            refreshes.incrementAndGet()
            refreshedDeclarations()
        }
    }

    private class CountingAdapter : QuerySchemaBackendAdapter {
        val resolves = AtomicInteger()
        val refreshes = AtomicInteger()
        val failRefresh = AtomicBoolean()
        val refreshFailure = QuerySchemaUnavailableException("Refresh failed.")

        override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = Mono.fromSupplier {
            resolves.incrementAndGet()
            newSchema()
        }

        override fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = Mono.defer {
            refreshes.incrementAndGet()
            if (failRefresh.get()) {
                Mono.error(refreshFailure)
            } else {
                Mono.just(newSchema())
            }
        }
    }

    private class FixedAdapter : QuerySchemaBackendAdapter {
        val resolves = AtomicInteger()
        private val initial = newSchema()
        private val refreshed = newSchema()

        override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = Mono.fromSupplier {
            resolves.incrementAndGet()
            initial
        }

        override fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = Mono.just(refreshed)
    }

    private class DelayedResolveAdapter : QuerySchemaBackendAdapter {
        val initial = newSchema()
        private val refreshed = newSchema()
        val initialResult = Sinks.one<QueryModelSchema>()
        val resolveSubscribed = CountDownLatch(1)

        override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = Mono.defer {
            resolveSubscribed.countDown()
            initialResult.asMono()
        }

        override fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = Mono.just(refreshed)
    }

    companion object {
        private val CONTEXT = QuerySchemaContext(
            MaterializedNamedAggregate("test-context", "test-aggregate"),
            QueryModel.SNAPSHOT,
        )

        private fun newSchema() = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = emptyMap(),
        )
    }
}
