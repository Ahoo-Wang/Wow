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

package me.ahoo.wow.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.concurrent.atomic.AtomicInteger

/** One PostgreSQL container for the test JVM, and a fresh context (so fresh tables) per log or snapshot store. */
object PostgresFixture {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17-alpine").also { it.start() }
    }

    val dataSource: HikariDataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = POOL_SIZE
            },
        )
    }

    private val contexts = AtomicInteger()

    fun newContext(prefix: String = "tck"): String = "$prefix-${contexts.incrementAndGet()}"

    fun log(partitions: Int, context: String = newContext()): PostgresEventLog =
        PostgresEventLog(dataSource, context, partitions).also { it.createSchema() }

    fun snapshotStore(context: String = newContext()): PostgresSnapshotStore =
        PostgresSnapshotStore(dataSource, context).also { it.createSchema() }

    /** Moves the recorded format version of [structure] one ahead, as a newer Wow would have written it. */
    fun bumpFormat(structure: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE ${PostgresFormat.TABLE} SET version = version + 1 WHERE structure = ?",
            ).use { update ->
                update.setString(1, structure)
                check(update.executeUpdate() == 1) { "$structure has no recorded format." }
            }
        }
    }

    private const val POOL_SIZE = 64
}
