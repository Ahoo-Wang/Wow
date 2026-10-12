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

package me.ahoo.wow.spi.snapshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The [SnapshotStore] contract (documentation/designs/v10/03-log-and-snapshot.md §3) every backend passes. A
 * backend's test extends it and supplies a fresh, empty store from [createStore].
 */
abstract class SnapshotStoreSpec {
    /** A new, empty snapshot store of bounded context [context]. */
    protected abstract fun createStore(context: String): SnapshotStore

    /** Each test gets its own context, so a backend that keeps snapshots per context isolates the tests. */
    private val context = "tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)
    private val type = AggregateType(context, "aggregate")

    private fun createStore(): SnapshotStore = createStore(context)

    protected fun snapshot(
        id: String,
        version: Int,
        tenant: String = "tenant",
        aggregate: AggregateType = type,
        ownerId: String? = "owner",
        spaceId: String? = "space",
        deleted: Boolean = false,
        tags: Map<String, List<String>> = mapOf("region" to listOf("east", "west"), "tier" to listOf("gold")),
    ) = SnapshotRecord(
        ref = aggregate.ref(tenant, id),
        version = version,
        schemaFingerprint = "fingerprint-$version",
        state = """{"id":"$id","version":$version,"text":"ü \" é","items":[{"sku":"a","quantity":2}],"note":null}""",
        ownerId = ownerId,
        spaceId = spaceId,
        deleted = deleted,
        firstEventTime = FIRST_EVENT_TIME,
        lastEventTime = FIRST_EVENT_TIME + version,
        tags = tags,
    )

    private fun SnapshotRecord?.assertSameAs(expected: SnapshotRecord) {
        this.assert().isNotNull()
        val loaded = checkNotNull(this)
        // A backend may store the state as JSON and give it back reformatted: compare it as JSON.
        json.readTree(loaded.state).assert().isEqualTo(json.readTree(expected.state))
        loaded.copy(state = expected.state).assert().isEqualTo(expected)
    }

    @Test
    fun `a snapshot loads back as saved`(): Unit = runBlocking {
        val store = createStore()
        val saved = snapshot("a", 3)
        store.save(saved).assert().isTrue()
        store.load(saved.ref).assertSameAs(saved)
    }

    @Test
    fun `absent access facts and the deleted mark load back as saved`(): Unit = runBlocking {
        val store = createStore()
        val saved = snapshot("a", 1, ownerId = null, spaceId = null, deleted = true, tags = emptyMap())
        store.save(saved).assert().isTrue()
        store.load(saved.ref).assertSameAs(saved)
    }

    @Test
    fun `only a higher version replaces a snapshot`(): Unit = runBlocking {
        val store = createStore()
        val second = snapshot("a", 2)
        store.save(second).assert().isTrue()
        store.save(snapshot("a", 1)).assert().isFalse()
        store.save(second.copy(state = """{"changed":true}""", ownerId = "other")).assert().isFalse()
        store.load(second.ref).assertSameAs(second)
        val third = snapshot("a", 3, ownerId = "other", deleted = true)
        store.save(third).assert().isTrue()
        store.load(third.ref).assertSameAs(third)
    }

    @Test
    fun `concurrent saves of different versions leave the highest`(): Unit = runBlocking {
        val store = createStore()
        val versions = (1..CONCURRENT_VERSIONS).shuffled()
        val written = coroutineScope {
            versions.map { version -> async(Dispatchers.Default) { store.save(snapshot("a", version)) } }.awaitAll()
        }
        written.assert().contains(true)
        store.load(type.ref("tenant", "a")).assertSameAs(snapshot("a", CONCURRENT_VERSIONS))
        store.save(snapshot("a", CONCURRENT_VERSIONS)).assert().isFalse()
    }

    @Test
    fun `two tenants and two aggregates may use the same id`(): Unit = runBlocking {
        val store = createStore()
        val other = AggregateType(context, "other")
        val first = snapshot("same", 5, tenant = "t1")
        val second = snapshot("same", 2, tenant = "t2")
        val third = snapshot("same", 1, tenant = "t1", aggregate = other)
        store.save(first).assert().isTrue()
        store.save(second).assert().isTrue()
        store.save(third).assert().isTrue()
        store.load(first.ref).assertSameAs(first)
        store.load(second.ref).assertSameAs(second)
        store.load(third.ref).assertSameAs(third)
    }

    @Test
    fun `a ref without a snapshot loads null`(): Unit = runBlocking {
        val store = createStore()
        store.save(snapshot("a", 1))
        store.load(type.ref("tenant", "missing")).assert().isNull()
        store.load(type.ref("nobody", "a")).assert().isNull()
    }

    protected companion object {
        private val json = JsonMapper.builder().build()
        const val CONTEXT_SUFFIX = 12
        const val CONCURRENT_VERSIONS = 32
        const val FIRST_EVENT_TIME = 1_700_000_000_000L
    }
}
