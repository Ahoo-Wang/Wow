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

package me.ahoo.wow.postgres.query

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.PostgresStore
import me.ahoo.wow.postgres.PostgresStoreProvider
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreResources
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/** A pool that says its size, as HikariCP's does. */
class SizedDataSource(private val size: Int) : DataSource by mockk() {
    @Suppress("unused") // Read by reflection, as HikariCP's getter.
    fun getMaximumPoolSize(): Int = size
}

/** What HikariCP's pool says of its waiting threads. */
interface AwaitingPool {
    fun getThreadsAwaitingConnection(): Int
}

/** A pool that says how many threads await a connection, as HikariCP's does, through a pool class that is not public. */
class AwaitingDataSource(private val awaiting: Int?) : DataSource by mockk() {
    private class Pool(private val awaiting: Int) : AwaitingPool {
        override fun getThreadsAwaitingConnection(): Int = awaiting
    }

    @Suppress("unused") // Read by reflection, as HikariCP's getter.
    fun getHikariPoolMXBean(): Any? = awaiting?.let(::Pool)
}

/** A pool whose pool bean says nothing of its waiting threads. */
class SilentDataSource : DataSource by mockk() {
    @Suppress("unused") // Read by reflection, as HikariCP's getter.
    fun getHikariPoolMXBean(): Any = Any()
}

/**
 * The PostgreSQL store's queries apart from its appends (review R3.4-2): the query `DataSource` its
 * `query-data-source` names, and the bound of its concurrent queries from the pool they draw from.
 */
class PostgresQueryProviderTest {
    private val provider = PostgresQueryProvider()

    private fun store(appends: DataSource, queries: DataSource = appends) =
        PostgresStore(StoreConfig.of("main", PostgresStoreProvider.TYPE), appends, queries)

    @Test
    fun `queries sharing the appends' pool should take a quarter of it, at least two`() {
        provider.maxConcurrent(store(SizedDataSource(10))).assert().isEqualTo(2)
        provider.maxConcurrent(store(SizedDataSource(4))).assert().isEqualTo(2)
        provider.maxConcurrent(store(SizedDataSource(40))).assert().isEqualTo(10)
        store(SizedDataSource(10)).queriesShareAppends.assert().isTrue()
    }

    @Test
    fun `queries on a pool of their own should take all of it`() {
        val queries = store(SizedDataSource(10), SizedDataSource(6))
        queries.queriesShareAppends.assert().isFalse()
        provider.maxConcurrent(queries).assert().isEqualTo(6)
    }

    @Test
    fun `a pool that does not say its size should have the default bound`() {
        val plain = mockk<DataSource>()
        plain.maximumPoolSize().assert().isNull()
        SizedDataSource(0).maximumPoolSize().assert().isNull()
        provider.maxConcurrent(store(plain)).assert().isEqualTo(StoreQueryProvider.DEFAULT_MAX_CONCURRENT)
    }

    @Test
    fun `the adaptive ceiling should be half of a shared pool, all of a pool of the queries' own (D252)`() {
        provider.maxConcurrentCeiling(store(SizedDataSource(10))).assert().isEqualTo(5)
        provider.maxConcurrentCeiling(store(SizedDataSource(4))).assert().isEqualTo(2)
        provider.maxConcurrentCeiling(store(SizedDataSource(10), SizedDataSource(6))).assert().isEqualTo(6)
        provider.maxConcurrentCeiling(store(mockk())).assert().isEqualTo(StoreQueryProvider.DEFAULT_MAX_CONCURRENT)
    }

    @Test
    fun `the store should say how many threads await a connection of the appends' pool, when the pool does (D252)`() {
        store(AwaitingDataSource(3)).waitingForConnection().assert().isEqualTo(3)
        store(AwaitingDataSource(null)).waitingForConnection().assert().isNull()
        store(SilentDataSource()).waitingForConnection().assert().isNull()
        store(mockk()).waitingForConnection().assert().isNull()
    }

    @Test
    fun `the store should read its queries through the DataSource query-data-source names`() {
        val appends = SizedDataSource(10)
        val reads = SizedDataSource(4)
        val resources = StoreResources.of(mapOf("appends" to appends, "reads" to reads))
        val opened = PostgresStoreProvider().open(
            StoreConfig.of(
                "main",
                PostgresStoreProvider.TYPE,
                options = mapOf(
                    PostgresStoreProvider.DATA_SOURCE to "appends",
                    PostgresStoreProvider.QUERY_DATA_SOURCE to "reads",
                ),
                resources = resources,
            ),
        ) as PostgresStore
        opened.dataSource.assert().isSameAs(appends)
        opened.queryDataSource.assert().isSameAs(reads)
        val shared = PostgresStoreProvider().open(
            StoreConfig.of(
                "main",
                PostgresStoreProvider.TYPE,
                options = mapOf(PostgresStoreProvider.DATA_SOURCE to "appends"),
                resources = resources,
            ),
        ) as PostgresStore
        shared.queryDataSource.assert().isSameAs(appends)
        val missing = runCatching {
            PostgresStoreProvider().open(
                StoreConfig.of(
                    "main",
                    PostgresStoreProvider.TYPE,
                    options = mapOf(
                        PostgresStoreProvider.DATA_SOURCE to "appends",
                        PostgresStoreProvider.QUERY_DATA_SOURCE to "none",
                    ),
                    resources = resources,
                ),
            )
        }.exceptionOrNull()
        missing!!.message.assert().contains("wow.stores.main.query-data-source")
    }

    @Test
    fun `the snapshot and events queries should be opened over the query DataSource`() {
        val queries = store(SizedDataSource(10), SizedDataSource(4))
        provider.snapshots(queries, StoreQueryOptions.DEFAULT).assert().isNotNull()
        provider.events(queries, StoreQueryOptions.DEFAULT).assert().isNotNull()
    }
}
