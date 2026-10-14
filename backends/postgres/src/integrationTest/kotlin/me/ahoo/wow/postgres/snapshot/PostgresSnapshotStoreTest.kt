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
import me.ahoo.wow.postgres.PostgresIdentifiers
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.snapshot.SnapshotStoreSpec
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
    fun `snapshots recorded in another format version are refused`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        PostgresFixture.snapshotStore(context).createTable("aggregate")
        PostgresFixture.bumpFormat(table(context, "aggregate"))
        assertThrownBy<IllegalStateException> { PostgresFixture.snapshotStore(context).createTable("aggregate") }
        val saved = snapshot("a", 1).copy(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
        assertThrownBy<IllegalStateException> { runBlocking { PostgresFixture.snapshotStore(context).save(saved) } }
    }

    @Test
    fun `snapshots created by a build without format records are refused`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.createUnrecorded(table(context, "aggregate"))
        assertThrownBy<IllegalStateException> { PostgresFixture.snapshotStore(context).createTable("aggregate") }
    }

    @Test
    fun `each aggregate type has a table of its own`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val store = PostgresFixture.snapshotStore(context)
        val order = snapshot("a", 1).copy(ref = AggregateType(context, "Order-Item").ref("tenant", "a"))
        val cart = snapshot("a", 2).copy(ref = AggregateType(context, "cart").ref("tenant", "a"))
        store.save(order).assert().isTrue()
        store.save(cart).assert().isTrue()
        store.load(order.ref)?.version.assert().isEqualTo(1)
        store.load(cart.ref)?.version.assert().isEqualTo(2)
        PostgresSnapshotTables(context, "Order-Item").base.assert().isEqualTo(table(context, "order_item"))
        PostgresSnapshotTables("c".repeat(40), "a".repeat(40)).base.length.assert().isEqualTo(PostgresIdentifiers.MAX_LENGTH)
    }

    @Test
    fun `a snapshot of another context is refused`(): Unit = runBlocking {
        val store = PostgresFixture.snapshotStore()
        val foreign = snapshot("a", 1).copy(ref = AggregateType("elsewhere", "aggregate").ref("tenant", "a"))
        assertThrownBy<IllegalArgumentException> { runBlocking { store.save(foreign) } }
        assertThrownBy<IllegalArgumentException> { runBlocking { store.load(foreign.ref) } }
    }

    private fun table(context: String, aggregate: String) = "wow_snapshot__" + context.replace('-', '_') + "__" + aggregate
}
