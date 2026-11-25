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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.tck.QueryBackendSpec
import me.ahoo.wow.query.tck.SnapshotDateBucketCases.Companion.GAP_MID_UNIT
import me.ahoo.wow.query.tck.SnapshotDateBucketCases.Companion.GAP_START
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/**
 * The query backend contract against a PostgreSQL database of the `C` locale, whose collation and character
 * classification know ASCII only: an answer that followed the database's own case functions would differ here from
 * the default locale's (#6773).
 */
class PostgresCLocaleQueryBackendTest : QueryBackendSpec() {
    override suspend fun store(snapshots: List<SnapshotRecord>) {
        val store = PostgresSnapshotStore(dataSource, type.context).also { it.createSchema() }
        snapshots.forEach { store.save(it) }
    }

    // As on the default locale's database (PostgresSnapshotQueryBackendTest): date_trunc reads a local time
    // Africa/Monrovia's 1972 change skipped at the offset after it.
    override val semanticDivergences: Set<String> = setOf(GAP_START, GAP_MID_UNIT)

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> =
        PostgresSnapshotQueryStorage(dataSource, type.context).binding(type)!!

    @Test
    fun `the database should be of the C locale`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT datcollate, datctype FROM pg_database WHERE datname = current_database()",
                ).use { rows ->
                    rows.next().assert().isTrue()
                    (rows.getString(1) to rows.getString(2)).assert().isEqualTo("C" to "C")
                }
            }
        }
    }

    private companion object {
        const val DATABASE = "wow_c_locale"

        /**
         * A database of the `C` locale beside the fixture's, created once for the test JVM. Its pool opens with the
         * first test and closes after the last, holding no idle connection: the container's 100 connections are every
         * test class's.
         */
        private val opened = lazy {
            PostgresFixture.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    val exists = statement.executeQuery("SELECT 1 FROM pg_database WHERE datname = '$DATABASE'")
                        .use { it.next() }
                    if (!exists) {
                        statement.execute(
                            "CREATE DATABASE $DATABASE TEMPLATE template0 ENCODING 'UTF8' LOCALE 'C'",
                        )
                    }
                }
            }
            val container = WowContainers.postgres
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = container.jdbcUrl.replace("/${container.databaseName}", "/$DATABASE")
                    username = container.username
                    password = container.password
                    maximumPoolSize = POOL_SIZE
                    minimumIdle = 0
                },
            )
        }
        val dataSource: HikariDataSource by opened

        @JvmStatic
        @AfterAll
        fun close() {
            if (opened.isInitialized()) {
                dataSource.close()
            }
        }

        const val POOL_SIZE = 4
    }
}
