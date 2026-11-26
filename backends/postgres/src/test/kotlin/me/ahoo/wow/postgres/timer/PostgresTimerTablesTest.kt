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

package me.ahoo.wow.postgres.timer

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class PostgresTimerTablesTest {
    /**
     * The statement every poll runs: each partition's first due timers over its table's partial index, nothing more. A
     * timer whose rule this version cannot read is the store's to skip ([PostgresTimerStore.due]); the statement that
     * reads on past such timers runs only when one filled a page ([PostgresTimerTables.dueAfter]).
     */
    @Test
    fun `the due read should stay the plain read of each partition's first due timers`() {
        val tables = PostgresTimerTables("shop")
        tables.due(listOf(tables.aggregate("order"))).assert().isEqualTo(
            "SELECT counter.registered, timer.* FROM (SELECT count(*) AS registered FROM wow_timer_aggregate " +
                "WHERE context = ?) AS counter LEFT JOIN LATERAL (SELECT * FROM (SELECT *, row_number() OVER " +
                "(PARTITION BY partition ORDER BY due_at, timer_id) AS partition_rank FROM ((SELECT 0 AS branch, * " +
                "FROM (SELECT due.* FROM unnest(?::integer[]) AS owned(partition) CROSS JOIN LATERAL (SELECT " +
                "${PostgresTimerTables.COLUMNS} FROM \"wow_timer__shop__order\" WHERE partition = owned.partition " +
                "AND status IN ('pending', 'firing') AND due_at <= ? ORDER BY due_at, timer_id LIMIT ?) AS due) " +
                "AS timers)) AS merged) AS ranked WHERE partition_rank <= ?) AS timer ON true " +
                "ORDER BY timer.due_at, timer.timer_id",
        )
    }

    @Test
    fun `the read on past skipped timers should take one partition after the row read last`() {
        val tables = PostgresTimerTables("shop")
        tables.dueAfter(listOf(tables.aggregate("order"))).assert().contains(
            "FROM \"wow_timer__shop__order\" WHERE partition = ? AND status IN ('pending', 'firing') AND due_at <= ? " +
                "AND (due_at, timer_id) > (?, ?) ORDER BY due_at, timer_id LIMIT ?",
        )
    }
}
