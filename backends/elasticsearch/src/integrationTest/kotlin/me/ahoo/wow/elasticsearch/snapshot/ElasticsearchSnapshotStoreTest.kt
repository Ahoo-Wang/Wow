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

package me.ahoo.wow.elasticsearch.snapshot

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.snapshot.SnapshotStoreSpec
import org.junit.jupiter.api.Test

class ElasticsearchSnapshotStoreTest : SnapshotStoreSpec() {
    override fun createStore(context: String): SnapshotStore = ElasticsearchFixture.store().snapshots(context)

    @Test
    fun `a store of another context refuses the ref`() {
        val store = createStore(ElasticsearchFixture.newContext())
        val ref = AggregateType(ElasticsearchFixture.newContext(), "aggregate").ref("tenant", "a")
        assertThrownBy<IllegalArgumentException> { runBlocking { store.load(ref) } }
    }

    @Test
    fun `validate refuses a missing index and none touches nothing`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val validating = ElasticsearchFixture.store(StoreDdl.VALIDATE).snapshots(context)
        val ref = AggregateType(context, "aggregate").ref("tenant", "a")
        assertThrownBy<IllegalStateException> { runBlocking { validating.load(ref) } }
        ElasticsearchFixture.store(StoreDdl.NONE).snapshots(context).load(ref).assert().isNull()
        // Created once, a validating store reads it.
        (ElasticsearchFixture.store().snapshots(context) as ElasticsearchSnapshotStore).createIndex("aggregate")
        validating.load(ref).assert().isNull()
    }

    @Test
    fun `an index of another layout is refused`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val index = ElasticsearchFixture.names.snapshots(context, "aggregate")
        ElasticsearchFixture.client.indices().create { it.index(index) }.get()
        val store = ElasticsearchFixture.store().snapshots(context)
        assertThrownBy<IllegalStateException> {
            runBlocking { store.load(AggregateType(context, "aggregate").ref("tenant", "a")) }
        }
    }

    @Test
    fun `a refused save marks a stale version once, and a deleted index loads nothing`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val store = createStore(context)
        val type = AggregateType(context, "aggregate")
        val newer = snapshot("a", 2, aggregate = type).copyWith(stateRevision = 2)
        store.save(newer).assert().isTrue()
        store.save(snapshot("a", 5, aggregate = type)).assert().isFalse()
        store.save(snapshot("a", 4, aggregate = type)).assert().isFalse()
        store.pending(type, 2, limit = 10).assert().containsExactly(newer.ref)
        ElasticsearchFixture.client.indices().delete { it.index(ElasticsearchFixture.names.snapshots(context, "aggregate")) }.get()
        store.load(newer.ref).assert().isNull()
    }
}
