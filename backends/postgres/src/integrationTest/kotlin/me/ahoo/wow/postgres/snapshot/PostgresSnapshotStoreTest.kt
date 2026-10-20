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
import me.ahoo.wow.postgres.CountedDataSource
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.postgres.PostgresIdentifiers
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.snapshot.SnapshotStoreSpec
import org.junit.jupiter.api.Test

class PostgresSnapshotStoreTest : SnapshotStoreSpec() {
    override fun createStore(context: String): SnapshotStore = PostgresFixture.snapshotStore(context)

    @Test
    fun `creating the schema twice keeps the snapshots`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val saved = snapshot("a", 1).copyWith(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
        PostgresFixture.snapshotStore(context).save(saved).assert().isTrue()
        PostgresFixture.snapshotStore(context).load(saved.ref)?.version.assert().isEqualTo(1)
    }

    @Test
    fun `snapshots recorded in another format version are refused`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        PostgresFixture.snapshotStore(context).createTable("aggregate")
        PostgresFixture.bumpFormat(table(context, "aggregate"))
        assertThrownBy<IllegalStateException> { PostgresFixture.snapshotStore(context).createTable("aggregate") }
        val saved = snapshot("a", 1).copyWith(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
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
        val order = snapshot("a", 1).copyWith(ref = AggregateType(context, "Order-Item").ref("tenant", "a"))
        val cart = snapshot("a", 2).copyWith(ref = AggregateType(context, "cart").ref("tenant", "a"))
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
        val foreign = snapshot("a", 1).copyWith(ref = AggregateType("elsewhere", "aggregate").ref("tenant", "a"))
        assertThrownBy<IllegalArgumentException> { runBlocking { store.save(foreign) } }
        assertThrownBy<IllegalArgumentException> { runBlocking { store.load(foreign.ref) } }
    }

    @Test
    fun `a batch of one aggregate type is one statement, and refusals cost one more`(): Unit = runBlocking {
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val context = PostgresFixture.newContext()
        val store = PostgresSnapshotStore(counted.dataSource, context)
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        fun of(type: AggregateType, id: String, version: Int) = snapshot(id, version).copyWith(ref = type.ref("tenant", id))
        store.save(of(order, "warm", 1)).assert().isTrue()
        store.save(of(cart, "warm", 1)).assert().isTrue()
        val batch = (1..BATCH).map { of(order, "o$it", 1) }
        val mark = counted.begin()
        store.saveAll(batch).forEach { it.assert().isEqualTo(SnapshotSaveOutcome.Written) }
        counted.since(mark).size.assert().isEqualTo(1)
        // Two aggregate types: one statement each.
        val twoTypes = counted.begin()
        store.saveAll((1..BATCH).flatMap { listOf(of(order, "o$it", 2), of(cart, "c$it", 1)) })
            .forEach { it.assert().isEqualTo(SnapshotSaveOutcome.Written) }
        counted.since(twoTypes).size.assert().isEqualTo(2)
        // Refused ones (a replay) add one statement, which marks those of a higher state revision stale.
        store.save(of(order, "newer", 3).copyWith(stateRevision = 2)).assert().isTrue()
        val replay = counted.begin()
        val outcomes = store.saveAll(listOf(of(order, "o1", 1), of(order, "o2", 3), of(order, "newer", 4)))
        outcomes.assert().containsExactly(
            SnapshotSaveOutcome.Refused,
            SnapshotSaveOutcome.Written,
            SnapshotSaveOutcome.Refused,
        )
        counted.since(replay).size.assert().isEqualTo(2)
        store.pending(order, 2, limit = 10).assert().contains(order.ref("tenant", "newer"))
    }

    @Test
    fun `a batch larger than one statement takes is cut into statements`(): Unit = runBlocking {
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val context = PostgresFixture.newContext()
        val store = PostgresSnapshotStore(counted.dataSource, context)
        val type = AggregateType(context, "aggregate")
        val rows = PostgresSnapshotTables.MAX_ROWS + 1
        val batch = (1..rows).map { snapshot("s$it", 1).copyWith(ref = type.ref("tenant", "s$it")) }
        store.createTable("aggregate")
        val mark = counted.begin()
        store.saveAll(batch).count { it.written }.assert().isEqualTo(rows)
        counted.since(mark).size.assert().isEqualTo(2)
        store.load(batch.last().ref)?.version.assert().isEqualTo(1)
    }

    @Test
    fun `a batch of another context is refused`(): Unit = runBlocking {
        val store = PostgresFixture.snapshotStore()
        val foreign = snapshot("a", 1).copyWith(ref = AggregateType("elsewhere", "aggregate").ref("tenant", "a"))
        assertThrownBy<IllegalArgumentException> { runBlocking { store.saveAll(listOf(foreign)) } }
    }

    private fun table(context: String, aggregate: String) = "wow_snapshot__" + context.replace('-', '_') + "__" + aggregate

    private companion object {
        const val BATCH = 20
    }
}
