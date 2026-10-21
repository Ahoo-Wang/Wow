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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.CountedDataSource
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.subscription.Checkpoint
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

    @Test
    fun `a batch of checkpoints is one statement batch and one commit`(): Unit = runBlocking {
        // B.O-5: in autocommit, each upsert of the JDBC batch was a transaction, and a WAL flush, of its own.
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val batched = PostgresCheckpointStore(counted.dataSource)
        val group = "batch-${System.nanoTime()}"
        val mark = counted.begin()
        val commits = counted.commits
        batched.saveAll(group, (0 until 8).map { Checkpoint.of("snapshot", it, 10L + it) })
        counted.since(mark).size.assert().isEqualTo(1)
        (counted.commits - commits).assert().isEqualTo(1)
        (0 until 8).map { batched.load(group, "snapshot", it) }.assert().isEqualTo((0 until 8).map { 10L + it })
        // A batch moves positions forward only, in one commit again.
        batched.saveAll(group, listOf(Checkpoint.of("snapshot", 0, 99), Checkpoint.of("snapshot", 1, 5)))
        (counted.commits - commits).assert().isEqualTo(2)
        batched.load(group, "snapshot", 0).assert().isEqualTo(99L)
        batched.load(group, "snapshot", 1).assert().isEqualTo(11L)
    }

    private companion object {
        val store by lazy { PostgresCheckpointStore(PostgresFixture.dataSource).also { it.createSchema() } }
    }
}
