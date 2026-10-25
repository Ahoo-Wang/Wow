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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.time.Duration.Companion.milliseconds

class PostgresTimeoutsTest {
    @Test
    fun `a call that outlasts its timeout fails as transient instead of waiting`() {
        // A server that does not answer in time stands for a reply lost on the way.
        val timed = PostgresTimeouts.timed(PostgresFixture.dataSource, SHORT)
        val failure = assertThrownBy<SQLException> {
            timed.connection.use { connection -> connection.createStatement().use { it.execute("SELECT pg_sleep(2)") } }
        }.actual()
        PostgresFailures.isTransient(failure).assert().isTrue()
        // The pool gives the next borrower a working connection.
        timed.connection.use { connection -> connection.createStatement().use { it.execute("SELECT 1") } }
    }

    @Test
    fun `the schema's statements have the longer timeout, the others the store's`(): Unit = runBlocking {
        val timed = PostgresTimeouts.timed(PostgresFixture.dataSource, SHORT)
        timed.connection.use { connection ->
            connection.networkTimeout.assert().isEqualTo(SHORT.inWholeMilliseconds.toInt())
            PostgresTimeouts.ddl(connection) {
                connection.networkTimeout.assert().isEqualTo(PostgresTimeouts.DDL.inWholeMilliseconds.toInt())
                connection.createStatement().use { it.execute("SELECT pg_sleep(0.5)") }
            }
            connection.networkTimeout.assert().isEqualTo(SHORT.inWholeMilliseconds.toInt())
        }
        // A store on the timed source creates its schema and reads as it did.
        val store = PostgresCheckpointStore(timed).also { it.createSchema() }
        store.load("timeouts", "probe", 0).assert().isNull()
    }

    @Test
    fun `one data source and timeout give one wrapper, so stores of one database find each other`() {
        // Decision D268: the log reads a snapshot with its tail when both stores are on one data source.
        val first = PostgresTimeouts.timed(PostgresFixture.dataSource, SHORT)
        PostgresTimeouts.timed(PostgresFixture.dataSource, SHORT).assert().isSameAs(first)
        PostgresTimeouts.timed(PostgresFixture.dataSource).assert().isNotSameAs(first)
        first.isWrapperFor(com.zaxxer.hikari.HikariDataSource::class.java).assert().isTrue()
    }

    private companion object {
        val SHORT = 300.milliseconds
    }
}
