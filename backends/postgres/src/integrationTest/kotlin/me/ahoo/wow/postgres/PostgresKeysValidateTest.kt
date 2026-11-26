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
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The keys and indexes of a store's tables, checked under `validate` and `auto` (#7062): a table Wow's schema made, or
 * one made by hand, that lacks a unique key its statements declare is refused, since the stores rely on it to refuse a
 * duplicate write; an index is made by `auto` and must exist under `validate`.
 */
class PostgresKeysValidateTest {
    private val dataSource = PostgresFixture.dataSource

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().use { s -> s.execute(sql) } }

    private fun log(context: String, ddl: Ddl = Ddl.AUTO) =
        PostgresEventLog(dataSource, context, 2, options = PostgresEventLogOptions(ddl = ddl))

    @Test
    fun `a log table whose version key was dropped should be refused, by validate and by auto`() {
        val context = PostgresFixture.newContext("keys")
        log(context).also {
            it.createSchema()
            it.createTable("order")
        }
        val table = PostgresEventLogTables(context).aggregate("order")
        execute("ALTER TABLE ${table.log} DROP CONSTRAINT \"${table.versionKey}\"")

        listOf(Ddl.VALIDATE, Ddl.AUTO).forEach { ddl ->
            assertThrows<IllegalStateException> { log(context, ddl).createTable("order") }.message.assert()
                .contains(table.base, "tenant_id, aggregate_id, version", table.versionKey)
        }
    }

    @Test
    fun `a key restored as another constraint over the same columns should pass`() {
        val context = PostgresFixture.newContext("keys")
        log(context).also {
            it.createSchema()
            it.createTable("order")
        }
        val table = PostgresEventLogTables(context).aggregate("order")
        execute("ALTER TABLE ${table.log} DROP CONSTRAINT \"${table.requestKey}\"")
        execute("CREATE UNIQUE INDEX \"restored_request\" ON ${table.log} (request_id, tenant_id)")

        log(context, Ddl.VALIDATE).createTable("order")
    }

    @Test
    fun `a snapshot table without its owner index should be refused by validate, and have it made by auto`() {
        val context = PostgresFixture.newContext("keys")
        PostgresSnapshotStore(dataSource, context).createTable("order")
        val tables = PostgresSnapshotTables(context, "order")
        execute("DROP INDEX \"${tables.ownerIndex}\"")

        assertThrows<IllegalStateException> {
            PostgresSnapshotStore(dataSource, context, ddl = Ddl.VALIDATE).createTable("order")
        }.message.assert().contains(tables.ownerIndex, "does not exist")
        PostgresSnapshotStore(dataSource, context).createTable("order")
        PostgresSnapshotStore(dataSource, context, ddl = Ddl.VALIDATE).createTable("order")
    }
}
