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

package me.ahoo.wow.postgres.snapshot

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotStoreSpec
import org.junit.jupiter.api.Test

class PostgresSnapshotStoreTest : SnapshotStoreSpec() {
    override fun createStore(context: String): SnapshotStore = PostgresFixture.snapshotStore(context)

    @Test
    fun `creating the schema twice keeps the snapshots`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val saved = snapshot("a", 1).copy(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
        PostgresFixture.snapshotStore(context).save(saved).assert().isTrue()
        PostgresFixture.snapshotStore(context).load(saved.ref)?.version.assert().isEqualTo(1)
    }

    @Test
    fun `a snapshot of another context is refused`(): Unit = runBlocking {
        val store = PostgresFixture.snapshotStore()
        val foreign = snapshot("a", 1).copy(ref = AggregateType("elsewhere", "aggregate").ref("tenant", "a"))
        assertThrownBy<IllegalArgumentException> { runBlocking { store.save(foreign) } }
        assertThrownBy<IllegalArgumentException> { runBlocking { store.load(foreign.ref) } }
    }
}
