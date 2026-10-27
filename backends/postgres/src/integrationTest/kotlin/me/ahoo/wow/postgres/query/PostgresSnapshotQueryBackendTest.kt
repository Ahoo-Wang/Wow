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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.tck.QueryBackendSpec
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test

/** The query backend contract against PostgreSQL: each test's fresh context is a fresh snapshot table. */
class PostgresSnapshotQueryBackendTest : QueryBackendSpec() {
    override suspend fun store(snapshots: List<SnapshotRecord>) {
        val store = PostgresFixture.snapshotStore(type.context)
        snapshots.forEach { store.save(it) }
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> =
        PostgresSnapshotQueryStorage(PostgresFixture.dataSource, type.context).create(type)

    @Test
    fun `a list consumed slowly holds no connection while it waits`(): Unit = runBlocking {
        // Finding A2-9: the rows were emitted inside the transaction, so a slow consumer held a pooled connection.
        val schema = given(orders()).schema
        val container = WowContainers.postgres
        val config = HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 1
            connectionTimeout = POOL_TIMEOUT_MS
        }
        HikariDataSource(config).use { pool ->
            val backend = PostgresSnapshotQueryStorage(pool, type.context).create(type).backend
            val first = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default) {
                backend.stream(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).map {
                    first.complete(Unit)
                    gate.await()
                }.toList()
            }
            withTimeout(TIMEOUT_MS) { first.await() }
            // The pool's one connection is free while the consumer waits.
            backend.countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema)).count
                .assert().isEqualTo(orders().size.toLong())
            gate.complete(Unit)
            withTimeout(TIMEOUT_MS) { reading.await() }.size.assert().isEqualTo(orders().size)
        }
    }

    private companion object {
        const val POOL_TIMEOUT_MS = 1_000L
        const val TIMEOUT_MS = 10_000L
    }
}
