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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.cache.QueryCountCache
import me.ahoo.wow.query.cache.QueryCountCacheOptions
import me.ahoo.wow.query.cache.SnapshotWriteCheckpoint
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * The count cache (decision D152, work package 6.2d) through the gateway, over the in-memory reference backend: a
 * repeated count is served from the cache, a snapshot write (the checkpoint moving) invalidates it, scopes and tenants
 * never share an entry, and bounded and exact counts are cached apart.
 */
class QueryCountCacheTest {
    private val storage = InMemoryQueryStorage()
    private val checkpoint = AtomicLong(1)
    private val counted = CopyOnWriteArrayList<PageWindow?>()
    private val outcomes = CopyOnWriteArrayList<String>()
    private var nanos = 0L

    private val observer = object : QueryObserver {
        override fun start(aggregateType: AggregateType, queryType: QueryType): Observation = object : Observation {
            override fun outcome(outcome: String) {
                outcomes += outcome
            }
        }
    }

    /** The in-memory backend, recording every count (`null`) and every page window it is asked for. */
    private inner class Recording(private val delegate: QueryBackend) : QueryBackend by delegate {
        var onCount: () -> Unit = {}

        override suspend fun count(query: AdmittedQuery<CountQuery>): Long {
            counted += null
            onCount()
            return delegate.count(query)
        }

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
            counted += window
            return delegate.page(query, window)
        }
    }

    private suspend fun gateway(
        cache: QueryCountCache = cache(),
        policies: List<QueryPolicy> = emptyList(),
    ): Pair<DefaultQueryGateway, Recording> {
        val backend = Recording(storage.create(ManifestFixtures.ORDER).backend)
        val gateway = DefaultQueryGateway(
            ManifestFixtures.ORDER,
            backend,
            FixedQueryModelSchemaProvider(ManifestFixtures.orderSchema()),
            policies = policies,
            observer = observer,
            counts = cache,
        )
        return gateway to backend
    }

    private fun cache(
        options: QueryCountCacheOptions = QueryCountCacheOptions(),
        checkpoint: SnapshotWriteCheckpoint = SnapshotWriteCheckpoint { this.checkpoint.get() },
    ) = QueryCountCache(checkpoint, options) { nanos }

    private fun write(id: String, tenant: String = "tenant", buyer: String = "Alice", version: Int = 1) {
        storage.save(order(id, tenant, buyer, version))
        checkpoint.incrementAndGet()
    }

    @Test
    fun `a repeated count should be served from the cache`() {
        runTest {
            write("o1")
            write("o2")
            val (gateway, _) = gateway()
            repeat(3) {
                gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS).assert()
                    .isEqualTo(CountResult(2, exact = true))
            }
            counted.assert().hasSize(1)
            outcomes.assert().containsExactly(QueryCountCache.CACHED_OUTCOME, QueryCountCache.CACHED_OUTCOME)
        }
    }

    @Test
    fun `a snapshot write should invalidate the cached count`() {
        runTest {
            write("o1")
            val (gateway, _) = gateway()
            val query = CountQuery(MatchAllFilter)
            gateway.count(query, QueryCaller.IN_PROCESS).count.assert().isEqualTo(1)
            // Written without the checkpoint moving: the cached count still holds, as the validator says.
            storage.save(order("o2"))
            gateway.count(query, QueryCaller.IN_PROCESS).count.assert().isEqualTo(1)
            checkpoint.incrementAndGet()
            gateway.count(query, QueryCaller.IN_PROCESS).count.assert().isEqualTo(2)
            gateway.count(query, QueryCaller.IN_PROCESS).count.assert().isEqualTo(2)
            counted.assert().hasSize(2)
        }
    }

    @Test
    fun `a write while counting should leave the count to be counted again`() {
        runTest {
            write("o1")
            val (gateway, backend) = gateway()
            backend.onCount = { checkpoint.incrementAndGet() }
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            backend.onCount = {}
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            counted.assert().hasSize(2)
        }
    }

    @Test
    fun `different tenants and scopes should never share a count`() {
        runTest {
            write("o1", tenant = "t1")
            write("o2", tenant = "t1")
            write("o3", tenant = "t2")
            val (gateway, _) = gateway(policies = listOf(ScopeQueryPolicy()))
            val query = CountQuery(MatchAllFilter)
            val t1 = QueryCaller(Caller(tenantId = "t1"))
            val t2 = QueryCaller(Caller(tenantId = "t2"))
            gateway.count(query, t1).count.assert().isEqualTo(2)
            gateway.count(query, t2).count.assert().isEqualTo(1)
            gateway.count(query, t1).count.assert().isEqualTo(2)
            gateway.count(query, t2).count.assert().isEqualTo(1)
            counted.assert().hasSize(2)

            // The same tenant in another owner's scope, or with a stated scope, is counted on its own.
            gateway.count(query, QueryCaller(Caller(tenantId = "t1", scope = Scope("other")))).count.assert()
                .isEqualTo(0)
            gateway.count(query, t1.withScope(QueryScope(declared = TenantIdFilter("t1")))).count.assert()
                .isEqualTo(2)
            counted.assert().hasSize(4)

            // A cross-tenant count of the same caller is its own, and never served to the caller's tenant.
            val across = t1.copy(crossTenant = CrossTenant.ALL)
            gateway.count(query, across).count.assert().isEqualTo(3)
            gateway.count(query, t1).count.assert().isEqualTo(2)
            gateway.count(query, across).count.assert().isEqualTo(3)
            counted.assert().hasSize(5)
        }
    }

    @Test
    fun `filters with different values should be cached apart`() {
        runTest {
            write("o1", buyer = "Alice")
            write("o2", buyer = "Bob")
            val (gateway, _) = gateway()
            val buyer = QueryField("state.buyer")
            gateway.count(CountQuery(EqualFilter(buyer, "Alice")), QueryCaller.IN_PROCESS).count.assert().isEqualTo(1)
            gateway.count(CountQuery(EqualFilter(buyer, "Carol")), QueryCaller.IN_PROCESS).count.assert().isEqualTo(0)
            gateway.count(CountQuery(EqualFilter(buyer, "Alice")), QueryCaller.IN_PROCESS).count.assert().isEqualTo(1)
            counted.assert().hasSize(2)
        }
    }

    @Test
    fun `bounded and exact counts should be cached apart`() {
        runTest {
            write("o1")
            write("o2")
            val (gateway, _) = gateway()
            val bounded = CountQuery(MatchAllFilter, limit = 1)
            val exact = CountQuery(MatchAllFilter)
            gateway.count(bounded, QueryCaller.IN_PROCESS).assert().isEqualTo(CountResult(1, exact = false))
            gateway.count(exact, QueryCaller.IN_PROCESS).assert().isEqualTo(CountResult(2, exact = true))
            gateway.count(bounded, QueryCaller.IN_PROCESS).assert().isEqualTo(CountResult(1, exact = false))
            gateway.count(exact, QueryCaller.IN_PROCESS).assert().isEqualTo(CountResult(2, exact = true))
            counted.assert().hasSize(2)
        }
    }

    @Test
    fun `a paged total should be counted with its page by default, so that it agrees with the rows (Q-12)`() {
        runTest {
            write("o1")
            val (gateway, _) = gateway()
            val query = PagedQuery(MatchAllFilter, pagination = Pagination(1, 1), total = TotalMode.EXACT)
            gateway.paged(query, QueryCaller.IN_PROCESS).total.assert().isEqualTo(1)
            // Another node's write, which this node's checkpoint does not see yet.
            write("o2")
            gateway.paged(query, QueryCaller.IN_PROCESS).total.assert().isEqualTo(2)
            outcomes.assert().isEmpty()
            QueryCountCacheOptions().pagedTotals.assert().isFalse()
        }
    }

    @Test
    fun `a paged total should be cached when asked, and the page read without counting`() {
        runTest {
            write("o1")
            write("o2")
            val (gateway, _) = gateway(cache(QueryCountCacheOptions(pagedTotals = true)))
            val pagination = Pagination(1, 1)
            listOf(TotalMode.BOUNDED, TotalMode.EXACT).forEach { mode ->
                val query = PagedQuery(MatchAllFilter, pagination = pagination, total = mode)
                val first = gateway.paged(query, QueryCaller.IN_PROCESS)
                val second = gateway.paged(query, QueryCaller.IN_PROCESS)
                second.total.assert().isEqualTo(2)
                second.exact.assert().isTrue()
                second.list.assert().isEqualTo(first.list)
            }
            counted.map { it?.let { window -> (window as PageWindow.Offset).withTotal } }.assert()
                .containsExactly(true, false, true, false)
            outcomes.assert().containsExactly(QueryCountCache.CACHED_OUTCOME, QueryCountCache.CACHED_OUTCOME)

            // The exact total is the exact count's: a count of the same filter is served from the paged total.
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS).count.assert().isEqualTo(2)
            counted.assert().hasSize(4)

            // A page without a total never reads the cache.
            gateway.paged(PagedQuery(MatchAllFilter, total = TotalMode.NONE), QueryCaller.IN_PROCESS).total
                .assert().isNull()
            counted.assert().hasSize(5)
        }
    }

    @Test
    fun `a count should expire after the ttl however long its checkpoint holds`() {
        runTest {
            write("o1")
            val (gateway, _) = gateway(cache(QueryCountCacheOptions(ttl = 1.seconds)))
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            nanos = Duration.ofMillis(999).toNanos()
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            nanos = Duration.ofSeconds(1).toNanos()
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            counted.assert().hasSize(2)
        }
    }

    @Test
    fun `the least recently used count should be evicted past the bound`() {
        runTest {
            write("o1")
            val cache = cache(QueryCountCacheOptions(maxEntries = 1))
            val (gateway, _) = gateway(cache)
            val buyer = QueryField("state.buyer")
            gateway.count(CountQuery(EqualFilter(buyer, "Alice")), QueryCaller.IN_PROCESS)
            gateway.count(CountQuery(EqualFilter(buyer, "Bob")), QueryCaller.IN_PROCESS)
            cache.size.assert().isEqualTo(1)
            gateway.count(CountQuery(EqualFilter(buyer, "Alice")), QueryCaller.IN_PROCESS)
            counted.assert().hasSize(3)
            cache.clear()
            cache.size.assert().isEqualTo(0)
            gateway.count(CountQuery(EqualFilter(buyer, "Alice")), QueryCaller.IN_PROCESS)
            counted.assert().hasSize(4)
        }
    }

    @Test
    fun `a count should not be cached when the checkpoint cannot be told or read`() {
        runTest {
            write("o1")
            val unknown = gateway(cache(checkpoint = { null })).first
            unknown.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            unknown.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            counted.assert().hasSize(2)

            val failing = gateway(cache(checkpoint = { error("checkpoint store down") })).first
            failing.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS).count.assert().isEqualTo(1)
            failing.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            counted.assert().hasSize(4)
            outcomes.assert().isEmpty()
        }
    }

    @Test
    fun `a cancellation while reading the checkpoint should propagate`() {
        runTest {
            val cancelled = gateway(cache(checkpoint = { throw CancellationException("cancelled") })).first
            val error = runCatching { cancelled.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS) }
            error.exceptionOrNull().assert().isInstanceOf(CancellationException::class.java)
            counted.assert().isEmpty()
        }
    }

    @Test
    fun `the options should be bounded`() {
        QueryCountCacheOptions().assert()
            .isEqualTo(QueryCountCacheOptions(QueryCountCacheOptions.DEFAULT_MAX_ENTRIES, 30.seconds))
        assertThrows<IllegalArgumentException> { QueryCountCacheOptions(maxEntries = 0) }
        assertThrows<IllegalArgumentException> { QueryCountCacheOptions(ttl = kotlin.time.Duration.ZERO) }
    }

    private companion object {
        fun order(id: String, tenant: String = "tenant", buyer: String = "Alice", version: Int = 1) = SnapshotRecord(
            ref = ManifestFixtures.ORDER.ref(tenant, id),
            version = version,
            schemaFingerprint = "test",
            state = """{"buyer":"$buyer","total":10}""",
            scope = Scope("owner"),
            firstEventTime = 1,
            lastEventTime = 1,
        )
    }
}
