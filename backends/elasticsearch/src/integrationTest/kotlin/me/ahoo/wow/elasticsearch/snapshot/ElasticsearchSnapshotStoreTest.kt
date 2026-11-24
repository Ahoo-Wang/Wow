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
import me.ahoo.wow.elasticsearch.ElasticsearchIndices
import me.ahoo.wow.elasticsearch.ElasticsearchStore
import me.ahoo.wow.elasticsearch.ElasticsearchStoreProvider
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.StorageRefusedException
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
    fun `an index of another layout behind the alias is refused`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val index = ElasticsearchFixture.names.snapshots(context, "aggregate")
        ElasticsearchFixture.client.indices().create {
            it.index(ElasticsearchIndices.concreteOf(index)).aliases(index) { alias -> alias.isWriteIndex(true) }
        }.get()
        val store = ElasticsearchFixture.store().snapshots(context)
        assertThrownBy<IllegalStateException> {
            runBlocking { store.load(AggregateType(context, "aggregate").ref("tenant", "a")) }
        }.hasMessageContaining("records the layout")
    }

    @Test
    fun `an index of the alias's own name, as a pre-release created it, is refused, naming what to do`(): Unit =
        runBlocking {
            val context = ElasticsearchFixture.newContext()
            val index = ElasticsearchFixture.names.snapshots(context, "aggregate")
            ElasticsearchFixture.client.indices().create { it.index(index) }.get()
            for (ddl in listOf(Ddl.AUTO, Ddl.VALIDATE)) {
                val store = ElasticsearchFixture.store(ddl).snapshots(context)
                assertThrownBy<IllegalStateException> {
                    runBlocking { store.load(AggregateType(context, "aggregate").ref("tenant", "a")) }
                }.hasMessage(ElasticsearchIndices.concreteIndex(index))
            }
        }

    @Test
    fun `an alias of several indices, or one that is not its index's write alias, is refused`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val several = ElasticsearchFixture.names.snapshots(context, "several")
        for (suffix in listOf("--1", "--2")) {
            ElasticsearchFixture.client.indices().create { create ->
                create.index(several + suffix).aliases(several) { alias -> alias }
            }.get()
        }
        val unwritten = ElasticsearchFixture.names.snapshots(context, "unwritten")
        ElasticsearchFixture.client.indices().create {
            it.index(ElasticsearchIndices.concreteOf(unwritten))
                .aliases(unwritten) { alias -> alias.isWriteIndex(false) }
        }.get()
        val store = ElasticsearchFixture.store().snapshots(context)
        assertThrownBy<IllegalStateException> {
            runBlocking { store.load(AggregateType(context, "several").ref("tenant", "a")) }
        }.hasMessageContaining("[$several] points at 2 indices")
        assertThrownBy<IllegalStateException> {
            runBlocking { store.load(AggregateType(context, "unwritten").ref("tenant", "a")) }
        }.hasMessageContaining("[$unwritten] is not the write alias")
    }

    @Test
    fun `an alias made by hand without is_write_index is its one index's write alias`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val type = AggregateType(context, "aggregate")
        val index = ElasticsearchFixture.names.snapshots(context, type.name)
        // As the operate page has an operator create the pair, but with the alias's flag left out.
        ElasticsearchFixture.client.indices().create {
            it.withJson(java.io.StringReader(ElasticsearchSnapshotIndex.DEFINITION))
                .index(ElasticsearchIndices.concreteOf(index)).aliases(index) { alias -> alias }
        }.get()
        val store = ElasticsearchFixture.store(Ddl.VALIDATE).snapshots(context)
        store.save(snapshot("a", 1, aggregate = type)).assert().isTrue()
        store.load(type.ref("tenant", "a"))?.version.assert().isEqualTo(1)
    }

    @Test
    fun `a single document Elasticsearch refuses fails its save as refused`(): Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val type = AggregateType(context, "aggregate")
        val store = createStore(context)
        // A field name Elasticsearch cannot map, written through the index API rather than a batch.
        assertThrownBy<StorageRefusedException> {
            runBlocking { store.save(snapshot("a", 1, aggregate = type).copyWith(state = """{"":1}""")) }
        }
    }

    @Test
    fun `under none a write to a missing index is refused, naming it, and creates nothing`(): Unit = runBlocking {
        // #6785: without the alias, Elasticsearch created the index on the first write, with a mapping it guessed.
        val context = ElasticsearchFixture.newContext()
        val type = AggregateType(context, "unmade")
        val index = ElasticsearchFixture.names.snapshots(context, type.name)
        val store = ElasticsearchFixture.store(Ddl.NONE).snapshots(context)
        assertThrownBy<IllegalStateException> { runBlocking { store.save(snapshot("a", 1, aggregate = type)) } }
            .hasMessage(ElasticsearchIndices.missingAlias(index))
        val outcome = store.saveAll(listOf(snapshot("b", 1, aggregate = type))).single()
        (outcome as SnapshotSaveOutcome.Failed).cause.message.assert()
            .isEqualTo(ElasticsearchIndices.missingAlias(index))
        ElasticsearchFixture.client.indices().exists { it.index(index) }.get().value().assert().isFalse()
        ElasticsearchFixture.client.indices().exists { it.index(ElasticsearchIndices.concreteOf(index)) }.get().value()
            .assert().isFalse()
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
        ElasticsearchFixture.client.indices().delete {
            it.index(ElasticsearchIndices.concreteOf(ElasticsearchFixture.names.snapshots(context, "aggregate")))
        }.get()
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
    fun `a batch to an index deleted under the store is refused, and under auto the next one creates the pair again`():
        Unit = runBlocking {
        val context = ElasticsearchFixture.newContext()
        val store = createStore(context)
        val type = AggregateType(context, "aggregate")
        val index = ElasticsearchFixture.names.snapshots(context, "aggregate")
        store.save(snapshot("a", 1, aggregate = type)).assert().isTrue()
        // The index is gone under the store, which made sure of it: each document reads as missing, alone, since the
        // multi-get could not read them, and the write is refused rather than creating an index of a guessed mapping.
        ElasticsearchFixture.client.indices().delete { it.index(ElasticsearchIndices.concreteOf(index)) }.get()
        val batch = listOf(snapshot("a", 2, aggregate = type), snapshot("b", 1, aggregate = type))
        store.saveAll(batch).forEach { outcome ->
            (outcome as SnapshotSaveOutcome.Failed).cause.message.assert()
                .isEqualTo(ElasticsearchIndices.missingAlias(index))
        }
        ElasticsearchFixture.client.indices().exists { it.index(index) }.get().value().assert().isFalse()
        store.saveAll(batch).assert().containsExactly(SnapshotSaveOutcome.Written, SnapshotSaveOutcome.Written)
        ElasticsearchFixture.client.indices().existsAlias { it.name(index) }.get().value().assert().isTrue()
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
            for (writer in 1..WRITERS) {
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
        ElasticsearchFixture.client.indices().create {
            it.withJson(java.io.StringReader(unassigned)).index(ElasticsearchIndices.concreteOf(index))
                .aliases(index) { alias -> alias.isWriteIndex(true) }.waitForActiveShards { active -> active.count(0) }
        }.get()
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

    @Test
    fun `a write that returned is read back by id at once at the default refresh, before any search sees it`(): Unit =
        runBlocking {
            val context = ElasticsearchFixture.newContext()
            val opened = ElasticsearchStoreProvider().open(ElasticsearchFixture.config(options = emptyMap()))
            val store = (opened as ElasticsearchStore).snapshots(context) as ElasticsearchSnapshotStore
            store.createIndex("aggregate")
            val index = ElasticsearchFixture.names.snapshots(context, "aggregate")
            // No refresh runs on its own: only a real-time read can see what is written next.
            ElasticsearchFixture.client.indices().putSettings {
                it.index(index).withJson(java.io.StringReader("""{"index.refresh_interval": "-1"}"""))
            }.get()
            val type = AggregateType(context, "aggregate")
            val ref = type.ref("tenant", "a")
            store.save(snapshot("a", 1, aggregate = type)).assert().isTrue()
            store.load(ref)?.version.assert().isEqualTo(1)
            // Each later write is guarded by the document it reads in real time.
            store.save(snapshot("a", 2, aggregate = type)).assert().isTrue()
            store.save(snapshot("a", 1, aggregate = type)).assert().isFalse()
            store.load(ref)?.version.assert().isEqualTo(2)
            store.saveAll(listOf(snapshot("a", 3, aggregate = type), snapshot("b", 1, aggregate = type)))
                .assert().containsExactly(SnapshotSaveOutcome.Written, SnapshotSaveOutcome.Written)
            store.load(ref)?.version.assert().isEqualTo(3)
            store.load(type.ref("tenant", "b"))?.version.assert().isEqualTo(1)
            ElasticsearchFixture.client.count { it.index(index) }.get().count().assert().isZero()
        }

    @Test
    fun `the longest id the rule takes, behind a long tenant, is saved and read back by its bounded document id`(): Unit =
        runBlocking {
            val context = ElasticsearchFixture.newContext()
            val store = createStore(context) as ElasticsearchSnapshotStore
            store.createIndex("aggregate")
            val type = AggregateType(context, "aggregate")
            // 512 bytes of id behind 64 characters of tenant: more than the 512 bytes Elasticsearch takes in an _id.
            val tenant = "t".repeat(LONG_TENANT)
            val ids = listOf("购".repeat(170) + "xx", "😀".repeat(128), "x".repeat(256))
            ids.forEach { id ->
                store.save(snapshot(id, 1, tenant = tenant, aggregate = type)).assert().isTrue()
                store.save(snapshot(id, 2, tenant = tenant, aggregate = type)).assert().isTrue()
                store.save(snapshot(id, 1, tenant = tenant, aggregate = type)).assert().isFalse()
                store.load(type.ref(tenant, id))?.version.assert().isEqualTo(2)
            }
            store.saveAll(ids.map { snapshot(it, 3, tenant = tenant, aggregate = type) })
                .assert().containsOnly(SnapshotSaveOutcome.Written)
            ids.map { store.load(type.ref(tenant, it))?.version }.assert().containsOnly(3)
            // One document each, its _id within the bytes Elasticsearch takes.
            val index = ElasticsearchFixture.names.snapshots(context, "aggregate")
            ElasticsearchFixture.client.indices().refresh { it.index(index) }.get()
            val documentIds = ElasticsearchFixture.client.search(
                { it.index(index).size(ids.size + 1) },
                Void::class.java,
            ).get().hits().hits().map { checkNotNull(it.id()) }
            documentIds.assert().hasSize(ids.size)
            documentIds.forEach { it.toByteArray(Charsets.UTF_8).size.assert().isLessThanOrEqualTo(512) }
        }

    @Test
    fun `a store that waits for the refresh keeps four writes in flight, the others the default two`() {
        fun inFlight(refresh: String?): Int {
            val options = refresh?.let { mapOf(ElasticsearchStoreProvider.REFRESH.key to it) } ?: emptyMap()
            val opened = ElasticsearchStoreProvider().open(ElasticsearchFixture.config(options = options))
            return (opened as ElasticsearchStore).snapshots(ElasticsearchFixture.newContext()).writesInFlight
        }
        inFlight("wait-for").assert().isEqualTo(4)
        inFlight("none").assert().isEqualTo(SnapshotStore.DEFAULT_WRITES_IN_FLIGHT)
        inFlight("immediate").assert().isEqualTo(SnapshotStore.DEFAULT_WRITES_IN_FLIGHT)
        inFlight(null).assert().isEqualTo(SnapshotStore.DEFAULT_WRITES_IN_FLIGHT)
    }

    private companion object {
        const val ALLOCATION_DELAY = 1_500L
        const val LONG_TENANT = 64
        const val ROUNDS = 10
        const val STORES = 8
        const val WRITERS = 64
    }
}
