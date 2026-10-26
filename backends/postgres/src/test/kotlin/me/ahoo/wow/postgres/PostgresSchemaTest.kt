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
import me.ahoo.wow.postgres.PostgresSchema.Target
import me.ahoo.wow.postgres.ledger.PostgresFailureLedgerTables
import me.ahoo.wow.postgres.reservation.PostgresReservationTables
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.postgres.timer.PostgresTimerTables
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

/** The catalog check of the schema's statements (finding F10): each statement of every store names what it makes. */
class PostgresSchemaTest {
    @Test
    fun `every statement of every store's schema should name what it makes`() {
        val log = PostgresEventLogTables("sales")
        val order = log.aggregate("order")
        val snapshots = PostgresSnapshotTables("sales", "order")
        val statements = log.ddl() + log.partitionAdditions + order.ddl() + order.additions + snapshots.ddl() +
            snapshots.additions + PostgresTimerTables("sales").ddl() + PostgresReservationTables("wow_reservation").ddl +
            PostgresFailureLedgerTables("wow_failure").ddl + PostgresCheckpointStore.DDL + PostgresLeaseStore.DDL
        statements.forEach { PostgresSchema.target(it) }
        statements.map { PostgresSchema.target(it) }.filterIsInstance<Target.Columns>().assert().isNotEmpty()
    }

    @Test
    fun `a table, an index and added columns should be read from their statements`() {
        PostgresSchema.target("CREATE TABLE IF NOT EXISTS \"wow_x\" (id text)")
            .assert().isEqualTo(Target.Relation("\"wow_x\"", "\"wow_x\""))
        PostgresSchema.target("CREATE INDEX IF NOT EXISTS \"x_ix\" ON \"wow_x\" (id)")
            .assert().isEqualTo(Target.Relation("\"x_ix\"", "\"wow_x\""))
        PostgresSchema.target("CREATE INDEX CONCURRENTLY IF NOT EXISTS \"x_q\" ON \"wow_x\" (id) WHERE NOT deleted")
            .assert().isEqualTo(Target.Relation("\"x_q\"", "\"wow_x\""))
        PostgresSchema.target(
            "ALTER TABLE \"wow_x\" ADD COLUMN IF NOT EXISTS a jsonb, ADD COLUMN IF NOT EXISTS b integer NOT NULL DEFAULT 1",
        ).assert().isEqualTo(Target.Columns("\"wow_x\"", listOf("a", "b")))
    }

    @Test
    fun `a statement the catalog cannot check should be refused`() {
        assertThrows<IllegalArgumentException> { PostgresSchema.target("CREATE TABLE \"wow_x\" (id text)") }
        assertThrows<IllegalArgumentException> { PostgresSchema.target("ALTER TABLE \"wow_x\" DROP COLUMN a") }
        assertThrows<IllegalArgumentException> { PostgresSchema.target("INSERT INTO wow_x VALUES (1)") }
    }

    @Test
    fun `a lock wait should be positive, and a step tried at least once`() {
        assertThrows<IllegalArgumentException> { PostgresSchema.LockWait(0.milliseconds, 1, 1.milliseconds) }
        assertThrows<IllegalArgumentException> { PostgresSchema.LockWait(1.milliseconds, 0, 1.milliseconds) }
    }
}
