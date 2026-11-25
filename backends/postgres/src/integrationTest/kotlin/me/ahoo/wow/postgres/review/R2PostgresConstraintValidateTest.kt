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

package me.ahoo.wow.postgres.review

import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.PostgresEventLogOptions
import me.ahoo.wow.postgres.PostgresEventLogTables
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Review r2 (storage formats): the PostgreSQL side of #6748. `PostgresFormat.validate` (`PostgresFormat.kt:87-102`)
 * compares a structure's format record and whether its table exists, nothing more, and `auto`'s create is
 * `CREATE TABLE IF NOT EXISTS`, which leaves an existing table as it is. A log table whose version key
 * (`UNIQUE (tenant_id, aggregate_id, version)`, `PostgresEventLogTables.kt:351`) was dropped passes both, though the
 * append's one guard against two commits of one version is that key (`:355-366`): `Ddl.VALIDATE` says it checks "that
 * each structure exists in the format this version reads", and `operate/postgresql.md` that "the database itself
 * refuses a second commit of the same version". MongoDB's `validate` checks its indexes since #6748.
 */
class R2PostgresConstraintValidateTest {
    private val dataSource = PostgresFixture.dataSource

    @Test
    fun `validate should refuse a log table whose version key was dropped`() {
        val context = PostgresFixture.newContext("r2ddl")
        PostgresEventLog(dataSource, context, 2).also {
            it.createSchema()
            it.createTable("order")
        }
        val table = PostgresEventLogTables(context).aggregate("order")
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("ALTER TABLE ${table.log} DROP CONSTRAINT \"${table.versionKey}\"")
            }
        }
        assertThrows<IllegalStateException> {
            PostgresEventLog(dataSource, context, 2, options = PostgresEventLogOptions(ddl = Ddl.VALIDATE))
                .createTable("order")
        }
    }
}
