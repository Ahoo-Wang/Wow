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
        columnsOf(PostgresTimerTables("shop").aggregate("order").ddl()).assert()
            .containsExactly(setOf("aggregate_tenant_id", "aggregate_id", "key"))
        columnsOf(PostgresReservationTables("shop", "order").ddl).assert()
            .containsExactly(setOf("tenant_id", "namespace", "key"))
        columnsOf(PostgresFailureLedgerTables("wow_failure_ledger").ddls).assert().hasSize(1)
        columnsOf(listOf(PostgresCheckpointStore.DDL)).assert()
            .containsExactly(setOf("subscription_group", "subscription", "partition"))
        columnsOf(PostgresLeaseStore.DDL).assert()
            .containsExactly(setOf("subscription_group", "partition"), setOf("service", "node"))
    }

    /**
     * Each store's unique keys exactly as [PostgresKeys.keys] finds them in its real schema statements: what the start
     * holds the catalog to. A key added to, dropped from or changed in a store's DDL changes this list.
     */
    @Test
    fun `each store's keys found in its real DDL should be pinned`() {
        val stores: Map<String, List<String>> = mapOf(
            "log registry and counters" to PostgresEventLogTables("shop").ddl(),
            "log table" to listOf(PostgresEventLogTables("shop").aggregate("order").ddl()),
            "snapshots" to PostgresSnapshotTables("shop", "order").ddl(),
            "timer registry" to PostgresTimerTables("shop").ddl(),
            "timers" to PostgresTimerTables("shop").aggregate("order").ddl(),
            "reservations" to PostgresReservationTables("shop", "order").ddl,
            "failure ledger" to PostgresFailureLedgerTables("wow_failure_ledger").ddls,
            "checkpoints" to listOf(PostgresCheckpointStore.DDL),
            "leases and members" to PostgresLeaseStore.DDL,
        )
        stores.mapValues { (_, ddl) -> columnsOf(ddl) }.assert().isEqualTo(
            mapOf(
                "log registry and counters" to listOf(
                    setOf("partition"),
                    setOf("log_table"),
                    setOf("context", "aggregate"),
                ),
                "log table" to listOf(
                    setOf("partition", "sequence"),
                    setOf("tenant_id", "aggregate_id", "version"),
                    setOf("tenant_id", "request_id"),
                ),
                "snapshots" to listOf(setOf("tenant_id", "aggregate_id"), setOf("structure")),
                "timer registry" to listOf(setOf("timer_table"), setOf("context", "aggregate")),
                "timers" to listOf(setOf("aggregate_tenant_id", "aggregate_id", "key")),
                "reservations" to listOf(setOf("tenant_id", "namespace", "key")),
                "failure ledger" to listOf(
                    setOf(
                        "service",
                        "subscription_context",
                        "subscription",
                        "context",
                        "aggregate",
                        "tenant_id",
                        "aggregate_id",
                    ),
                ),
                "checkpoints" to listOf(setOf("subscription_group", "subscription", "partition")),
                "leases and members" to listOf(setOf("subscription_group", "partition"), setOf("service", "node")),
            ),
        )
    }

    @Test
    fun `a quoted column should keep its case, an unquoted one be folded, and a check be no key`() {
        val statement = """CREATE TABLE IF NOT EXISTS t (Id text, "Key" text, n integer, """ +
            """CONSTRAINT "k" UNIQUE (Id, "Key"), CONSTRAINT "positive" CHECK (n > 0))"""
        columnsOf(listOf(statement)).assert().containsExactly(setOf("id", "Key"))
    }
}
