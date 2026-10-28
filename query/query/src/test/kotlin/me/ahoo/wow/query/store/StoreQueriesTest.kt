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

package me.ahoo.wow.query.store

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.gatewaySchema
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StorePool
import me.ahoo.wow.spi.store.facet
import org.junit.jupiter.api.Test

/** The values and defaults of the store query SPI (review R3.3-1), and the bounds a store's pool gives its queries. */
class StoreQueriesTest {
    private val type = AggregateType("shop", "order")

    private fun pool(queries: Int?, shared: Boolean) = object : StorePool {
        override val queryConnections: Int? = queries
        override val sharedWithAppends: Boolean = shared
        override fun waitingForConnection(): Int? = null
    }

    @Test
    fun `a store's queries query nothing unless they say so, and a store has no facet unless it says so`() {
        val queries = object : StoreQueries {}
        queries.snapshots(StoreQueryOptions.DEFAULT).assert().isNull()
        queries.events(StoreQueryOptions.DEFAULT).assert().isNull()
        val store = object : Store(StoreConfig.of("a", "acme")) {
            override val location: Any = Any()

            override fun openCheckpoints() = error("unused")

            override fun openLeases() = error("unused")

            override fun openLedger() = error("unused")

            override fun openReservations() = error("unused")

            override fun openLog(context: String, partitions: Int) = error("unused")

            override fun openSnapshots(context: String) = error("unused")

            override fun openTimers(context: String, partitions: Int) = error("unused")
        }
        store.facet<StoreQueries>().assert().isNull()
        store.facet<StorePool>().assert().isNull()
    }

    @Test
    fun `a pool the appends share should give the queries a quarter, at least two, and the ceiling half`() {
        StoreQueryBounds.maxConcurrent(pool(10, shared = true)).assert().isEqualTo(2)
        StoreQueryBounds.maxConcurrent(pool(4, shared = true)).assert().isEqualTo(2)
        StoreQueryBounds.maxConcurrent(pool(40, shared = true)).assert().isEqualTo(10)
        StoreQueryBounds.ceiling(pool(10, shared = true)).assert().isEqualTo(5)
        StoreQueryBounds.ceiling(pool(4, shared = true)).assert().isEqualTo(2)
    }

    @Test
    fun `a pool of the queries' own should be theirs whole, and an unknown pool the default`() {
        StoreQueryBounds.maxConcurrent(pool(6, shared = false)).assert().isEqualTo(6)
        StoreQueryBounds.ceiling(pool(6, shared = false)).assert().isEqualTo(6)
        StoreQueryBounds.maxConcurrent(pool(null, shared = true)).assert()
            .isEqualTo(StoreQueryBounds.DEFAULT_MAX_CONCURRENT)
        StoreQueryBounds.ceiling(pool(null, shared = false)).assert().isEqualTo(StoreQueryBounds.DEFAULT_MAX_CONCURRENT)
        StoreQueryBounds.maxConcurrent(null).assert().isEqualTo(StoreQueryBounds.DEFAULT_MAX_CONCURRENT)
        StoreQueryBounds.ceiling(null).assert().isEqualTo(StoreQueryBounds.DEFAULT_MAX_CONCURRENT)
    }

    @Test
    fun `snapshot queries manage no index unless they say so`() {
        val storage = InMemoryQueryStorage()
        val queries = object : SnapshotQueries {
            override fun binding(key: AggregateType): QueryBackendBinding<QueryBackend> = storage.binding(key)
        }
        queries.managesIndexes.assert().isFalse()
        queries.prepare(type)
        queries.binding(type).backend.aggregateType.assert().isEqualTo(type)
        queries.manageIndexes(
            type,
            QueryIndexPlan.of(gatewaySchema(), emptyList())
        ).assert().isSameAs(QueryIndexReport.NONE)
    }

    @Test
    fun `an index report and the options are values`() {
        val report = QueryIndexReport(created = listOf("a"), missing = listOf("b"), invalid = listOf("c"))
        report.assert().isEqualTo(
            QueryIndexReport(listOf("a"), listOf("b"), listOf("c"))
        ).isNotEqualTo(QueryIndexReport.NONE)
        report.hashCode().assert().isEqualTo(QueryIndexReport(listOf("a"), listOf("b"), listOf("c")).hashCode())
        report.toString().assert().isEqualTo("QueryIndexReport(created=[a], missing=[b], invalid=[c])")
        val options = StoreQueryOptions.of(Ddl.VALIDATE, "secondaryPreferred")
        options.indexDdl.assert().isEqualTo(Ddl.VALIDATE)
        options.eventsReadPreference.assert().isEqualTo("secondaryPreferred")
        options.toString().assert().contains("VALIDATE", "secondaryPreferred")
        StoreQueryOptions.DEFAULT.indexDdl.assert().isEqualTo(Ddl.AUTO)
        StoreQueryOptions.DEFAULT.eventsReadPreference.assert().isNull()
    }
}
