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

package me.ahoo.wow.tck.snapshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import me.ahoo.wow.tck.ExactJson
import org.junit.jupiter.api.Test
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
        scope: Scope = Scope("owner", "space"),
        deleted: Boolean = false,
        tags: Map<String, List<String>> = mapOf("region" to listOf("east", "west"), "tier" to listOf("gold")),
        operator: Operator? = Operator("bob", tenant),
        firstOperator: Operator? = Operator("alice ü", "home", crossScope = true),
        schedules: Map<String, ScheduleRule> = mapOf(
            "pay-timeout" to ScheduleRule.After(1_800_000),
            "report" to ScheduleRule.Cron("0 0 9 * * *", "Asia/Shanghai", Misfire.CATCH_UP),
            "remind" to ScheduleRule.At(FIRST_EVENT_TIME + 60_000),
        ),
    ) = SnapshotRecord(
        ref = aggregate.ref(tenant, id),
        version = version,
        schemaFingerprint = "fingerprint-$version",
        state = """{"id":"$id","version":$version,"text":"ü \" é","items":[{"sku":"a","quantity":2}],"note":null}""",
        scope = scope,
        deleted = deleted,
        firstEventTime = FIRST_EVENT_TIME,
        lastEventTime = FIRST_EVENT_TIME + version,
        tags = tags,
        operator = operator,
        firstOperator = firstOperator,
        schedules = schedules,
    )

    private fun SnapshotRecord?.assertSameAs(expected: SnapshotRecord) {
        this.assert().isNotNull()
        val loaded = checkNotNull(this)
        // A backend may store the state as JSON and give it back reformatted: compare it as JSON.
        ExactJson.of(loaded.state).assert().isEqualTo(ExactJson.of(expected.state))
        loaded.copyWith(state = expected.state).assert().isEqualTo(expected)
    }

    @Test
    fun `a snapshot loads back as saved`(): Unit = runBlocking {
        val store = createStore()
        val saved = snapshot("a", 3)
        store.save(saved).assert().isTrue()
        store.load(saved.ref).assertSameAs(saved)
    }

    @Test
    fun `a state with a NUL character loads back as saved, or is refused as a value`(): Unit = runBlocking {
        // Finding A2-7: PostgreSQL's jsonb refuses it with a driver exception, MongoDB keeps it. Admission refuses it
        // first; a store that sees it all the same keeps it or refuses it as a value, never as another failure.
        val store = createStore()
        val saved = snapshot("a", 1).copyWith(state = """{"name":"a\u0000b","n\u0000":1}""")
        val outcome = runCatching { store.save(saved) }
        val refused = outcome.exceptionOrNull()
        if (refused == null) {
            outcome.getOrNull().assert().isEqualTo(true)
            store.load(saved.ref).assertSameAs(saved)
        } else {
            refused.assert().isInstanceOf(IllegalArgumentException::class.java)
            store.load(saved.ref).assert().isNull()
        }
    }

    @Test
    fun `state numbers load back exactly as saved`(): Unit = runBlocking {
        val store = createStore()
        val saved = snapshot("a", 1).copyWith(state = ExactJson.NUMBERS)
        store.save(saved).assert().isTrue()
        store.load(saved.ref).assertSameAs(saved)
    }

    @Test
    fun `state numbers in exponent form load back with their value`(): Unit = runBlocking {
        val store = createStore()
        // How Jackson writes a double: PostgreSQL JSONB gives `1.0E10` back as `10000000000`, with the same value.
        val state = """{"amount":1.0E10,"tiny":1.0E-7,"negative":-2.5E-3,"huge":1.0E21,"keys":{"b":1.5E2,"a":2}}"""
        val saved = snapshot("a", 1).copyWith(state = state)
        store.save(saved).assert().isTrue()
        val loaded = checkNotNull(store.load(saved.ref))
        ExactJson.valueOf(loaded.state).assert().isEqualTo(ExactJson.valueOf(state))
        loaded.copyWith(state = state).assert().isEqualTo(saved)
    }

    @Test
    fun `absent access facts and the deleted mark load back as saved`(): Unit = runBlocking {
        val store = createStore()
        val saved = snapshot(
            "a",
            1,
            scope = Scope.NONE,
            deleted = true,
            tags = emptyMap(),
            operator = null,
            firstOperator = null,
            schedules = emptyMap(),
        )
        store.save(saved).assert().isTrue()
        store.load(saved.ref).assertSameAs(saved)
        val ownerOnly = snapshot("b", 1, scope = Scope(ownerId = "owner"))
        val spaceOnly = snapshot("c", 1, scope = Scope(spaceId = "space"))
        store.save(ownerOnly).assert().isTrue()
        store.save(spaceOnly).assert().isTrue()
        store.load(ownerOnly.ref).assertSameAs(ownerOnly)
        store.load(spaceOnly.ref).assertSameAs(spaceOnly)
    }

    @Test
    fun `only a higher version replaces a snapshot`(): Unit = runBlocking {
        val store = createStore()
        val second = snapshot("a", 2)
        store.save(second).assert().isTrue()
        store.save(snapshot("a", 1)).assert().isFalse()
        store.save(second.copyWith(state = """{"changed":true}""", scope = Scope(ownerId = "other"))).assert().isFalse()
        store.load(second.ref).assertSameAs(second)
        val third = snapshot("a", 3, scope = Scope(ownerId = "other"), deleted = true)
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

    @Test
    fun `the state revision loads back as saved`(): Unit = runBlocking {
        val store = createStore()
        val saved = snapshot("a", 1).copyWith(stateRevision = 3)
        store.save(saved).assert().isTrue()
        store.load(saved.ref).assertSameAs(saved)
        val legacy = snapshot("b", 1)
        store.save(legacy).assert().isTrue()
        checkNotNull(store.load(legacy.ref)).stateRevision.assert().isEqualTo(1)
    }

    @Test
    fun `a lower state revision never replaces a higher one but marks it stale`(): Unit = runBlocking {
        val store = createStore()
        val newer = snapshot("a", 2).copyWith(stateRevision = 2)
        store.save(newer).assert().isTrue()
        store.pending(type, 2, limit = 10).assert().isEmpty()
        // An old node of revision 1 commits version 3 and cannot write it over the revision 2 snapshot.
        store.save(snapshot("a", 3)).assert().isFalse()
        store.load(newer.ref).assertSameAs(newer)
        store.pending(type, 2, limit = 10).assert().containsExactly(newer.ref)
        // A save that is not newer marks nothing.
        val other = snapshot("b", 2).copyWith(stateRevision = 2)
        store.save(other).assert().isTrue()
        store.save(snapshot("b", 1)).assert().isFalse()
        store.pending(type, 2, limit = 10).assert().containsExactly(newer.ref)
        // A higher state revision replaces a lower one at a higher version.
        val upgraded = snapshot("c", 1)
        store.save(upgraded).assert().isTrue()
        val third = snapshot("c", 2).copyWith(stateRevision = 2)
        store.save(third).assert().isTrue()
        store.load(third.ref).assertSameAs(third)
    }

    @Test
    fun `a rewrite replaces the same version or a lower one whatever the revision`(): Unit = runBlocking {
        val store = createStore()
        val stored = snapshot("a", 4).copyWith(stateRevision = 2)
        store.save(stored).assert().isTrue()
        // A rollback rebuilds at revision 1, in place, at the same version.
        val rebuilt = snapshot("a", 4).copyWith(state = """{"rebuilt":true}""")
        store.rewrite(rebuilt).assert().isTrue()
        store.load(rebuilt.ref).assertSameAs(rebuilt)
        store.rewrite(snapshot("a", 3).copyWith(stateRevision = 2)).assert().isFalse()
        store.load(rebuilt.ref).assertSameAs(rebuilt)
        val later = snapshot("a", 5).copyWith(stateRevision = 2)
        store.rewrite(later).assert().isTrue()
        store.load(later.ref).assertSameAs(later)
        val missing = snapshot("b", 2)
        store.rewrite(missing).assert().isTrue()
        store.load(missing.ref).assertSameAs(missing)
    }

    @Test
    fun `a stale mark keeps a snapshot pending until a rewrite reaches its version`(): Unit = runBlocking {
        val store = createStore()
        val stored = snapshot("a", 2)
        store.save(stored).assert().isTrue()
        store.pending(type, 1, limit = 10).assert().isEmpty()
        store.markStale(stored.ref).assert().isTrue()
        store.load(stored.ref).assertSameAs(stored)
        store.pending(type, 1, limit = 10).assert().containsExactly(stored.ref)
        store.rewrite(stored).assert().isTrue()
        store.pending(type, 1, limit = 10).assert().isEmpty()
        store.markStale(type.ref("tenant", "missing")).assert().isFalse()
        // A refused save of version 5 over a higher revision: only a rewrite of version 5 or later clears it.
        val newer = snapshot("b", 3).copyWith(stateRevision = 2)
        store.save(newer).assert().isTrue()
        store.save(snapshot("b", 5)).assert().isFalse()
        store.rewrite(snapshot("b", 4)).assert().isTrue()
        store.pending(type, 1, limit = 10).assert().containsExactly(newer.ref)
        store.rewrite(snapshot("b", 5)).assert().isTrue()
        store.pending(type, 1, limit = 10).assert().isEmpty()
        // A save of a higher version keeps a mark of a version above it.
        store.save(snapshot("c", 2).copyWith(stateRevision = 2)).assert().isTrue()
        store.save(snapshot("c", 6)).assert().isFalse()
        store.save(snapshot("c", 4).copyWith(stateRevision = 2)).assert().isTrue()
        val c = type.ref("tenant", "c")
        store.pending(type, 2, limit = 10).assert().contains(c)
        store.save(snapshot("c", 6).copyWith(stateRevision = 2)).assert().isTrue()
        store.pending(type, 2, limit = 10).assert().doesNotContain(c)
    }

    @Test
    fun `pending snapshots come in key order, page by page, of one type`(): Unit = runBlocking {
        val store = createStore()
        val other = AggregateType(context, "other")
        listOf("t2" to "a", "t1" to "b", "t1" to "a", "t1" to "a~", "t1" to "aé").forEach { (tenant, id) ->
            store.save(snapshot(id, 1, tenant = tenant)).assert().isTrue()
        }
        store.save(snapshot("current", 1, tenant = "t1").copyWith(stateRevision = 2)).assert().isTrue()
        store.save(snapshot("a", 1, tenant = "t1", aggregate = other)).assert().isTrue()
        val expected = listOf("t1" to "a", "t1" to "a~", "t1" to "aé", "t1" to "b", "t2" to "a").map { (tenant, id) ->
            type.ref(tenant, id)
        }
        store.pending(type, 2, limit = 10).assert().isEqualTo(expected)
        val first = store.pending(type, 2, limit = 2)
        first.assert().isEqualTo(expected.take(2))
        val second = store.pending(type, 2, first.last(), limit = 2)
        second.assert().isEqualTo(expected.subList(2, 4))
        store.pending(type, 2, second.last(), limit = 2).assert().isEqualTo(expected.subList(4, 5))
        store.pending(type, 2, expected.last(), limit = 2).assert().isEmpty()
        store.pending(type, 1, limit = 10).assert().containsExactly(type.ref("t1", "current"))
        store.pending(AggregateType(context, "none"), 1, limit = 10).assert().isEmpty()
    }

    @Test
    fun `a table's first claim records it at the revision, not yet backfilled`(): Unit = runBlocking {
        val store = createStore()
        val other = AggregateType(context, "other")
        store.table(type).assert().isNull()
        store.claim(type, 1).assert().isEqualTo(SnapshotTable(1, rebuilding = false, backfilled = false))
        store.table(type).assert().isEqualTo(SnapshotTable(1, rebuilding = false, backfilled = false))
        store.claim(other, 2).assert().isEqualTo(SnapshotTable(2, rebuilding = true, backfilled = false))
        store.claim(type, 1).assert().isEqualTo(SnapshotTable(1, rebuilding = false, backfilled = false))
    }

    @Test
    fun `a claim raises the revision, and lowers it only for a rollback`(): Unit = runBlocking {
        val store = createStore()
        store.claim(type, 1)
        store.mark(type, 1, backfilled = true).assert().isEqualTo(SnapshotTable(1, rebuilding = false))
        store.claim(type, 2).assert().isEqualTo(SnapshotTable(2, rebuilding = true))
        store.mark(type, 1, rebuilding = false).assert().isNull()
        store.mark(type, 2, rebuilding = false).assert().isEqualTo(SnapshotTable(2))
        store.mark(type, 2).assert().isEqualTo(SnapshotTable(2))
        store.mark(type, 1).assert().isNull()
        store.claim(type, 1).assert().isEqualTo(SnapshotTable(2))
        store.claim(type, 2, lower = true).assert().isEqualTo(SnapshotTable(2))
        store.claim(type, 1, lower = true).assert().isEqualTo(SnapshotTable(1, rebuilding = true))
        store.table(type).assert().isEqualTo(SnapshotTable(1, rebuilding = true))
        store.mark(AggregateType(context, "unclaimed"), 1, rebuilding = false).assert().isNull()
    }

    @Test
    fun `concurrent claims leave one revision`(): Unit = runBlocking {
        val store = createStore()
        val claimed = coroutineScope {
            (1..CONCURRENT_VERSIONS).map { index ->
                async(Dispatchers.Default) { store.claim(type, index % 3 + 1) }
            }.awaitAll()
        }
        claimed.forEach { it.revision.assert().isBetween(1, 3) }
        store.table(type)!!.revision.assert().isEqualTo(3)
        store.claim(type, 3).revision.assert().isEqualTo(3)
    }

    protected companion object {
        const val CONTEXT_SUFFIX = 12
        const val CONCURRENT_VERSIONS = 32
        const val FIRST_EVENT_TIME = 1_700_000_000_000L
    }
}
