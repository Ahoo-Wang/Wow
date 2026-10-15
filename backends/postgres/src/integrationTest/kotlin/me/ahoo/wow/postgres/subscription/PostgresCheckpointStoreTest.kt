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

package me.ahoo.wow.postgres.subscription

import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.tck.subscription.CheckpointStoreSpec
import org.junit.jupiter.api.Test

class PostgresCheckpointStoreTest : CheckpointStoreSpec() {
    override fun createStore(): CheckpointStore = store

    @Test
    fun `creating the schema twice is harmless`() {
        PostgresCheckpointStore(PostgresFixture.dataSource).createSchema()
        store.assert().isNotNull()
    }

    @Test
    fun `the checkpoint and lease tables record their format`() {
        // Finding A2-13: a layout change went unnoticed until a statement failed on a missing column.
        store.assert().isNotNull()
        PostgresLeaseStore(PostgresFixture.dataSource).createSchema()
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT structure, version FROM wow_format WHERE structure IN " +
                    "('wow_checkpoint', 'wow_lease', 'wow_lease_member') ORDER BY structure",
            ).use { query ->
                query.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) to rows.getInt(2)) }
                }
            }
        }.assert().containsExactly("wow_checkpoint" to 1, "wow_lease" to 1, "wow_lease_member" to 1)
    }

    private companion object {
        val store by lazy { PostgresCheckpointStore(PostgresFixture.dataSource).also { it.createSchema() } }
    }
}
