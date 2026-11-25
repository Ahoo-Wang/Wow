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
import me.ahoo.wow.postgres.ledger.PostgresFailureLedgerTables
import me.ahoo.wow.postgres.reservation.PostgresReservationTables
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.postgres.timer.PostgresTimerTables
import org.junit.jupiter.api.Test

/** The keys and indexes the stores' schema statements declare ([PostgresKeys]), read from the statements themselves. */
class PostgresKeysTest {
    private fun columnsOf(
        statements: List<String>
    ): List<Set<String>> = PostgresKeys.keys(statements).map { it.columns }

    @Test
    fun `a log table's keys should be its position, its version and its request`() {
        val table = PostgresEventLogTables("shop").aggregate("order")
        columnsOf(listOf(table.ddl())).assert().containsExactly(
            setOf("partition", "sequence"),
            setOf("tenant_id", "aggregate_id", "version"),
            setOf("tenant_id", "request_id"),
        )
        PostgresKeys.keys(listOf(table.ddl()))[1].declared.assert().contains(table.versionKey)
        PostgresKeys.indexes(listOf(table.ddl())).assert().isEmpty()
    }

    @Test
    fun `a column's own key and a registry's two keys should be read`() {
        columnsOf(PostgresEventLogTables("shop").ddl()).assert().containsExactly(
            setOf("partition"),
            setOf("log_table"),
            setOf("context", "aggregate"),
        )
        columnsOf(PostgresTimerTables("shop").ddl()).assert().containsExactly(
            setOf("timer_table"),
            setOf("context", "aggregate"),
        )
    }

    @Test
    fun `every store's tables should declare their keys, and their indexes be named`() {
        val snapshots = PostgresSnapshotTables("shop", "order")
        columnsOf(snapshots.ddl()).assert().contains(setOf("tenant_id", "aggregate_id"), setOf("structure"))
        PostgresKeys.indexes(snapshots.ddl()).map { it.name }.assert()
            .containsExactly("\"${snapshots.ownerIndex}\"", "\"${snapshots.spaceIndex}\"")
        PostgresKeys.indexes(snapshots.ddl()).none { it.unique }.assert().isTrue()
        columnsOf(PostgresTimerTables("shop").aggregate("order").ddl()).assert()
            .containsExactly(setOf("owner_tenant_id", "owner_id", "key"))
        columnsOf(PostgresReservationTables("shop", "order").ddl).assert()
            .containsExactly(setOf("tenant_id", "namespace", "key"))
        columnsOf(PostgresFailureLedgerTables("wow_failure_ledger").ddls).assert().hasSize(1)
        columnsOf(listOf(PostgresCheckpointStore.DDL)).assert()
            .containsExactly(setOf("subscription_group", "subscription", "partition"))
        columnsOf(PostgresLeaseStore.DDL).assert()
            .containsExactly(setOf("subscription_group", "partition"), setOf("subscription_group", "node"))
    }

    @Test
    fun `a quoted column should keep its case, an unquoted one be folded`() {
        val statement = """CREATE TABLE IF NOT EXISTS t (Id text, "Key" text, CONSTRAINT "k" UNIQUE (Id, "Key"))"""
        columnsOf(listOf(statement)).assert().containsExactly(setOf("id", "Key"))
        PostgresKeys.indexes(listOf("""CREATE UNIQUE INDEX IF NOT EXISTS "t_u" ON t (a)""")).single().unique.assert()
            .isTrue()
    }
}
