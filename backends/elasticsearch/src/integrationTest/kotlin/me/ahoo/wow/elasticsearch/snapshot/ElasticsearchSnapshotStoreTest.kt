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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
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
        val validating = ElasticsearchFixture.store(Ddl.VALIDATE).snapshots(context)
        val ref = AggregateType(context, "aggregate").ref("tenant", "a")
        assertThrownBy<IllegalStateException> { runBlocking { validating.load(ref) } }
        ElasticsearchFixture.store(Ddl.NONE).snapshots(context).load(ref).assert().isNull()
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
        store.rebuilds!!.pending(type, 2, limit = 10).assert().containsExactly(newer.ref)
        ElasticsearchFixture.client.indices().delete { it.index(ElasticsearchFixture.names.snapshots(context, "aggregate")) }.get()
        store.load(newer.ref).assert().isNull()
    }

    @Test
    fun `a batch's document Elasticsearch refuses fails alone`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val store = createStore(context)
        val type = AggregateType(context, "aggregate")
        val saved = snapshot("a", 1, aggregate = type)
        // A field name Elasticsearch cannot map.
        val refused = snapshot("b", 1, aggregate = type).copyWith(state = """{"":1}""")
        val outcomes = store.saveAll(listOf(saved, refused))
        outcomes[0].assert().isEqualTo(SnapshotSaveOutcome.Written)
        (outcomes[1] as SnapshotSaveOutcome.Failed).cause.assert().isInstanceOf(IllegalArgumentException::class.java)
        store.load(saved.ref)?.version.assert().isEqualTo(1)
        store.load(refused.ref).assert().isNull()
    }

    @Test
    fun `a batch reads alone what its multi-get could not read`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val store = createStore(context)
        val type = AggregateType(context, "aggregate")
        store.save(snapshot("a", 1, aggregate = type)).assert().isTrue()
        // The index is gone under the store, which made sure of it: each document reads as missing.
        ElasticsearchFixture.client.indices().delete { it.index(ElasticsearchFixture.names.snapshots(context, "aggregate")) }.get()
        val outcomes = store.saveAll(listOf(snapshot("a", 2, aggregate = type), snapshot("b", 1, aggregate = type)))
        outcomes.assert().containsExactly(SnapshotSaveOutcome.Written, SnapshotSaveOutcome.Written)
    }

    @Test
    fun `many first writes to a fresh index, from several stores at once, all land`(): Unit = runBlocking {
        repeat(ROUNDS) {
            val context = ElasticsearchFixture.newContext()
            val type = AggregateType(context, "aggregate")
            val stores = (1..STORES).map { createStore(context) }
            val saved = coroutineScope {
                (1..WRITERS).map { writer ->
                    async(Dispatchers.Default) {
                        stores[writer % STORES].save(snapshot("w$writer", 1, aggregate = type))
                    }
                }.awaitAll()
            }
            saved.assert().doesNotContain(false)
            (1..WRITERS).forEach { writer ->
                stores.first().load(type.ref("tenant", "w$writer"))?.version.assert().isEqualTo(1)
            }
        }
    }

    @Test
    fun `a first write waits for an index whose primary is not active yet`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val index = ElasticsearchFixture.names.snapshots(context, "aggregate")
        // The index exists, as another node just created it, and its primary is not assigned yet.
        val unassigned = ElasticsearchSnapshotIndex.DEFINITION.replace(
            "\"index.mapping.ignore_malformed\": true",
            "\"index.mapping.ignore_malformed\": true, \"index.routing.allocation.require._name\": \"nowhere\"",
        )
        ElasticsearchFixture.client.indices().create { it.index(index).waitForActiveShards { active -> active.count(0) }.withJson(java.io.StringReader(unassigned)) }.get()
        val type = AggregateType(context, "aggregate")
        val saving = async(Dispatchers.Default) { createStore(context).save(snapshot("a", 1, aggregate = type)) }
        delay(ALLOCATION_DELAY)
        saving.isCompleted.assert().isFalse()
        ElasticsearchFixture.client.indices().putSettings {
            it.index(index).withJson(java.io.StringReader("""{"index.routing.allocation.require._name": null}"""))
        }.get()
        saving.await().assert().isTrue()
        createStore(context).load(type.ref("tenant", "a"))?.version.assert().isEqualTo(1)
    }

    private companion object {
        const val ALLOCATION_DELAY = 1_500L
        const val ROUNDS = 10
        const val STORES = 8
        const val WRITERS = 64
    }
}
