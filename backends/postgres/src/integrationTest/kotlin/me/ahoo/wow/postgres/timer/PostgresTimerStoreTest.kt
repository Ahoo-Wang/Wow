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

import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.postgres.PostgresFixture
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

    private companion object {
        /** One store for every test: they keep to timers of their own. */
        val store: PostgresTimerStore by lazy {
            PostgresTimerStore(PostgresFixture.dataSource, "timer-tck").also { it.createSchema() }
        }
    }
}
