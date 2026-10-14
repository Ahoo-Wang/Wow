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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.timer.TimerStoreSpec
import org.junit.jupiter.api.Test

class PostgresTimerStoreTest : TimerStoreSpec() {
    override fun createStore(): TimerStore = store

    @Test
    fun `creating the schema twice is harmless`() {
        PostgresTimerStore(PostgresFixture.dataSource, "timer-twice").createSchema()
        PostgresTimerStore(PostgresFixture.dataSource, "timer-twice").createSchema()
    }

    @Test
    fun `a table recorded in another format is refused`() {
        val context = PostgresFixture.newContext("timer")
        PostgresTimerStore(PostgresFixture.dataSource, context).createSchema()
        PostgresFixture.bumpFormat(PostgresTimerTables(context).base)
        assertThrownBy<IllegalStateException> { PostgresTimerStore(PostgresFixture.dataSource, context).createSchema() }
    }

    @Test
    fun `a table of format 2, without the slot's due time, is refused`() {
        val context = PostgresFixture.newContext("timer")
        val base = PostgresTimerTables(context).base
        PostgresTimerStore(PostgresFixture.dataSource, context).createSchema()
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE ${PostgresFormat.TABLE} SET version = 2 WHERE structure = ?").use {
                it.setString(1, base)
                it.executeUpdate().assert().isEqualTo(1)
            }
        }
        assertThrownBy<IllegalStateException> { PostgresTimerStore(PostgresFixture.dataSource, context).createSchema() }
    }

    @Test
    fun `a table created before the skipped count gets it, each timer having skipped none`(): Unit = runBlocking {
        val context = PostgresFixture.newContext("timer")
        val tables = PostgresTimerTables(context)
        PostgresTimerStore(PostgresFixture.dataSource, context).createSchema()
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("ALTER TABLE ${tables.table} DROP COLUMN skipped") }
        }
        val store = PostgresTimerStore(PostgresFixture.dataSource, context).also { it.createSchema() }
        val ref = AggregateType("shop", "order").ref("t", "o-1")
        val timer = Timer(
            id = "$ref#k@1",
            owner = ref.toString(),
            key = "k",
            version = 1,
            partition = 0,
            rule = ScheduleRule.Cron("0 0 0 1 * *"),
            dueAt = 5,
            target = ref,
            commandType = "shop.order.Invoice",
            body = "{}",
            slot = 4,
            skipped = 3,
        )
        store.put(timer).assert().isTrue()
        store.find(timer.owner, "k")?.skipped.assert().isEqualTo(3L)
    }

    private companion object {
        /** One store for every test: they keep to timers of their own. */
        val store: PostgresTimerStore by lazy {
            PostgresTimerStore(PostgresFixture.dataSource, "timer-tck").also { it.createSchema() }
        }
    }
}
