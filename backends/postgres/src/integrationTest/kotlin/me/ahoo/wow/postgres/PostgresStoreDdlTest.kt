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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.timer.PostgresTimerStore
import me.ahoo.wow.postgres.timer.PostgresTimerTables
import me.ahoo.wow.spi.StoreDdl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The stores' DDL modes (review A5-10): `auto` creates, `validate` only checks, `none` leaves the schema alone. */
class PostgresStoreDdlTest {
    private val dataSource = PostgresFixture.dataSource

    private fun tableExists(table: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?",
        ).use { query ->
            query.setString(1, table)
            query.executeQuery().use { it.next() }
        }
    }

    @Test
    fun `validate refuses a missing log, and passes once auto created it`() {
        val context = PostgresFixture.newContext("ddl")
        val validating = PostgresEventLog(dataSource, context, 2, ddl = StoreDdl.VALIDATE)
        assertThrows<IllegalStateException> { validating.createSchema() }.message.assert().contains("no format record")

        PostgresEventLog(dataSource, context, 2).also {
            it.createSchema()
            it.createTable("order")
        }
        validating.createSchema()
        validating.createTable("order")
        assertThrows<IllegalStateException> { validating.createTable("cart") }.message.assert()
            .contains("no format record")
    }

    @Test
    fun `validate refuses a structure recorded in another format`() {
        val context = PostgresFixture.newContext("ddl")
        PostgresEventLog(dataSource, context, 2).also {
            it.createSchema()
            it.createTable("order")
        }
        PostgresFixture.bumpFormat(PostgresEventLogTables(context).aggregate("order").base)
        assertThrows<IllegalStateException> {
            PostgresEventLog(dataSource, context, 2, ddl = StoreDdl.VALIDATE).createTable("order")
        }.message.assert().contains("Migrate it")
    }

    @Test
    fun `none creates and checks nothing`() {
        val context = PostgresFixture.newContext("ddl")
        PostgresTimerStore(dataSource, context, ddl = StoreDdl.NONE).createSchema()
        PostgresEventLog(dataSource, context, 2, ddl = StoreDdl.NONE).createTable("order")
        tableExists(PostgresTimerTables(context).aggregate("order").base).assert().isFalse()
    }
}
