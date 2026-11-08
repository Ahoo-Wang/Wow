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

package me.ahoo.wow.query.policy

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.cache.QueryCountCache
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetAccess
import me.ahoo.wow.query.dataset.QueryDatasetScope
import me.ahoo.wow.query.dataset.QueryDatasetTest
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.engine.ReadFacade
import me.ahoo.wow.query.events.UnpinnedEventsPolicy
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.AccessFixtures.DEPARTMENT
import me.ahoo.wow.query.policy.AccessFixtures.DOCUMENT
import me.ahoo.wow.query.policy.AccessFixtures.DocumentState
import me.ahoo.wow.query.policy.AccessFixtures.LEDGER
import me.ahoo.wow.query.policy.AccessFixtures.LEVEL
import me.ahoo.wow.query.policy.AccessFixtures.LedgerState
import me.ahoo.wow.query.policy.AccessFixtures.MANIFEST
import me.ahoo.wow.query.policy.AccessFixtures.NOTE
import me.ahoo.wow.query.policy.AccessFixtures.NoteState
import me.ahoo.wow.query.policy.AccessFixtures.REGION
import me.ahoo.wow.query.policy.AccessFixtures.caller
import me.ahoo.wow.query.policy.AccessFixtures.documents
import me.ahoo.wow.query.policy.AccessFixtures.record
import me.ahoo.wow.query.policy.AccessFixtures.state
import me.ahoo.wow.query.policy.AccessFixtures.stream
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.state.StateVisibility
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.json.query.EventStreamFields
import me.ahoo.wow.spi.json.query.SnapshotFields
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The reads of the aggregates an access policy governs, through the engine a host builds (D390 B2, B3). */
class AccessQueryEngineTest {
    private val storage = InMemoryQueryStorage().apply {
        save(record(DOCUMENT, "d-sales", mapOf("department" to "sales", "sharedWith" to emptyList<String>())))
        save(record(DOCUMENT, "d-hr", mapOf("department" to "hr", "sharedWith" to listOf("bob"), "level" to 2L)))
        // Recorded before its department was set.
        save(record(DOCUMENT, "d-none"))
        save(record(DOCUMENT, "d-other", mapOf("department" to "sales"), tenantId = "other"))
        save(record(NOTE, "n-alice", owner = "alice"))
        save(record(NOTE, "n-zed"))
        save(record(LEDGER, "l-1", owner = "alice"))
    }

    private val streams = listOf(
        stream(DOCUMENT, "d-sales", mapOf("department" to "sales", "sharedWith" to emptyList<String>())),
        stream(DOCUMENT, "d-hr", mapOf("department" to "hr", "sharedWith" to listOf("bob"), "level" to 2L)),
        // Committed before the stamping, and not stamped yet.
        stream(DOCUMENT, "d-none", null),
        stream(NOTE, "n-alice", emptyMap(), owner = "alice"),
        stream(NOTE, "n-zed", emptyMap()),
        // Committed by zed, its aggregate since transferred to alice: stamped with its owner of now.
        stream(NOTE, "n-moved", emptyMap(), owner = "alice", committed = Scope("zed")),
        stream(NOTE, "n-space", emptyMap(), space = "s1"),
        stream(NOTE, "n-unstamped", null, owner = "alice"),
    )
    private val events = InMemoryEventQueryStorage({ type -> streams.filter { it.ref.type == type } })

    private fun engine(
        vararg policies: AccessPolicy<*>,
        counts: QueryCountCache? = null,
        eventCounts: QueryCountCache? = null,
    ): QueryEngine =
        QueryEngine.Builder(MANIFEST)
            .snapshots(storage)
            .events(events)
            .accessPolicies(policies.toList(), AccessFixtures::aggregatesOf)
            .counts(counts, eventCounts)
            .build()

    private suspend fun QueryEngine.streams(
        type: AggregateType,
        caller: QueryCaller,
        filter: FilterExpression = MatchAllFilter,
    ): List<String> = events(type)!!.list(ListQuery(filter, limit = 100), caller).toList()
        .map { it[EventStreamFields.AGGREGATE_ID].stringValue() }

    private suspend fun QueryEngine.pinned(type: AggregateType): Boolean =
        events(type)!!.describe(QueryEntry.HTTP).constraints.any { it.type == UnpinnedEventsPolicy.AGGREGATE_REQUIRED }

    private suspend fun QueryEngine.ids(type: AggregateType, caller: QueryCaller): List<String> =
        snapshots(type)!!.list(ListQuery(MatchAllFilter, limit = 100), caller).toList()
            .map { it[SnapshotFields.AGGREGATE_ID].stringValue() }

    private val alice = caller("alice", "departments" to listOf("sales"))
    private val auditor = caller("carol", "roles" to listOf("auditor"))

    @Test
    fun `a governed aggregate's snapshots should be read as its policy lets each caller read them`(): Unit =
        runBlocking {
            val engine = engine(documents)
            engine.access.governed.assert().containsExactly(DOCUMENT)
            // Not by the caller's owner: the documents are zed's, and the policy alone decides.
            engine.ids(DOCUMENT, alice).assert().containsExactly("d-sales")
            engine.ids(DOCUMENT, caller("bob")).assert().containsExactly("d-hr")
            engine.ids(DOCUMENT, auditor).assert().containsExactlyInAnyOrder("d-sales", "d-hr", "d-none")
            // A caller without the claims the policy compares reads nothing.
            engine.ids(DOCUMENT, caller(null)).assert().isEmpty()
            engine.ids(DOCUMENT, caller("alice", "departments" to listOf("sales"), tenantId = "other"))
                .assert().containsExactly("d-other")
        }

    @Test
    fun `a policy should compare the owner and the space of each snapshot, whatever the caller's scope`(): Unit =
        runBlocking {
            storage.save(record(NOTE, "n-space", space = "s1"))
            val notes = accessPolicy<NoteState> {
                readWrite { caller ->
                    anyOf(AccessAttribute.OWNER eq caller.subject, AccessAttribute.SPACE eq caller.claim("space"))
                }
            }
            val engine = engine(documents, notes)
            engine.ids(NOTE, caller("alice")).assert().containsExactly("n-alice")
            engine.ids(NOTE, caller("bob", "space" to listOf("s1"))).assert().containsExactly("n-space")
            // A caller acting in a space of its own reads the documents by their policy, not by that space.
            engine.ids(DOCUMENT, caller("bob", scope = Scope("bob", "s9"))).assert().containsExactly("d-hr")
            // On the events, the owner and space each stream records, the aggregate's of now.
            engine.streams(NOTE, caller("alice")).assert().containsExactlyInAnyOrder("n-alice", "n-moved")
            engine.streams(NOTE, caller("bob", "space" to listOf("s1"))).assert().containsExactly("n-space")
        }

    @Test
    fun `an aggregate without a policy should still be read by its caller's owner and space`(): Unit = runBlocking {
        val engine = engine(documents)
        engine.ids(NOTE, alice).assert().containsExactly("n-alice")
        engine.ids(NOTE, caller("emma", scope = Scope.NONE)).assert().containsExactlyInAnyOrder("n-alice", "n-zed")
    }

    @Test
    fun `trusted code and a service reading as itself should be read by no access policy, the tenant bounding the service`(): Unit =
        runBlocking {
            val engine = engine(documents)
            engine.ids(DOCUMENT, QueryCaller.IN_PROCESS).assert()
                .containsExactlyInAnyOrder("d-sales", "d-hr", "d-none", "d-other")
            engine.ids(DOCUMENT, QueryCaller(Caller.ofService("basket", "acme"))).assert()
                .containsExactlyInAnyOrder("d-sales", "d-hr", "d-none")
            // A service's call that carries an end user is that user's.
            engine.ids(DOCUMENT, caller("bob", "app" to listOf("basket"))).assert().containsExactly("d-hr")
        }

    @Test
    fun `a state read should be admitted by the same policy, by its latest record`(): Unit = runBlocking {
        val engine = engine(documents)
        val hr = state(record(DOCUMENT, "d-hr", mapOf("department" to "hr", "sharedWith" to listOf("bob"))))
        engine.reads.admitted(alice, hr).assert().isNull()
        engine.reads.admitted(caller("bob"), hr)!!["title"].stringValue().assert().isEqualTo("d-hr")
        // Its history is read as the aggregate is now: masked, and checked by no policy again.
        engine.reads.history(hr)["title"].stringValue().assert().isEqualTo("d-hr")
        ReadFacade.NONE.history(hr)["title"].stringValue().assert().isEqualTo("d-hr")
        StateVisibility.masked(null, hr)["title"].stringValue().assert().isEqualTo("d-hr")
    }

    @Test
    fun `an attribute the aggregate does not have, and the owner of a tenant's aggregate, should match nothing`(): Unit =
        runBlocking {
            val regional = accessPolicy<DocumentState> {
                read { caller -> anyOf(REGION eq caller.claim("region"), allowIf(caller.hasRole("auditor"))) }
                write { denyAll() }
            }
            val ledgers = accessPolicy<LedgerState> {
                readWrite { caller ->
                    anyOf(AccessAttribute.OWNER eq caller.subject, allowIf(caller.hasRole("auditor")))
                }
            }
            val engine = engine(regional, ledgers)
            engine.ids(DOCUMENT, caller("alice", "region" to listOf("eu"))).assert().isEmpty()
            engine.ids(DOCUMENT, auditor).assert().hasSize(3)
            engine.ids(LEDGER, caller("alice")).assert().isEmpty()
            engine.ids(LEDGER, auditor).assert().containsExactly("l-1")
        }

    @Test
    fun `an integer attribute should be compared as the records store it`(): Unit = runBlocking {
        val leveled = accessPolicy<DocumentState> {
            read { caller -> LEVEL isIn caller.claims("levels").map(String::toInt) }
            write { denyAll() }
        }
        engine(leveled).ids(DOCUMENT, caller("alice", "levels" to listOf("2", "3"))).assert().containsExactly("d-hr")
    }

    @Test
    fun `the events of a governed aggregate should be read by its policy on what each stream records`(): Unit =
        runBlocking {
            val cache = QueryCountCache({ 1L })
            val engine = engine(documents, eventCounts = cache)
            // An events query need not name its aggregates.
            engine.pinned(DOCUMENT).assert().isFalse()
            engine.streams(DOCUMENT, alice).assert().containsExactly("d-sales")
            engine.streams(DOCUMENT, caller("bob")).assert().containsExactly("d-hr")
            engine.streams(DOCUMENT, caller("bob"), AggregateIdFilter("d-sales")).assert().isEmpty()
            // A stream not stamped yet shows to no caller a policy reads for.
            engine.streams(DOCUMENT, auditor).assert().containsExactlyInAnyOrder("d-sales", "d-hr")
            engine.streams(DOCUMENT, caller(null)).assert().isEmpty()
            engine.streams(DOCUMENT, QueryCaller(Caller.ofService("basket", "acme"))).assert()
                .containsExactlyInAnyOrder("d-sales", "d-hr", "d-none")
            val gateway = engine.events(DOCUMENT)!!
            gateway.count(CountQuery(MatchAllFilter), alice).count.assert().isEqualTo(1)
            gateway.count(CountQuery(MatchAllFilter), auditor).count.assert().isEqualTo(2)
            cache.size.assert().isEqualTo(2)
        }

    @Test
    fun `an aggregate without a policy should show its events to its owner and space of now`(): Unit = runBlocking {
        val engine = engine(documents)
        engine.pinned(NOTE).assert().isFalse()
        engine.streams(NOTE, caller("alice")).assert().containsExactlyInAnyOrder("n-alice", "n-moved")
        // Its committer no longer owns it; a stream not stamped yet shows to no owner.
        engine.streams(NOTE, caller("zed")).assert().containsExactlyInAnyOrder("n-zed", "n-space")
        engine.streams(NOTE, caller("emma", scope = Scope(spaceId = "s1"))).assert().containsExactly("n-space")
        // A filter of the query's own on the owner reads the one recorded at the commit.
        engine.streams(NOTE, caller("alice"), OwnerIdFilter("zed")).assert().containsExactly("n-moved")
        engine.streams(NOTE, caller("emma", scope = Scope.NONE)).assert().hasSize(5)
    }

    @Test
    fun `a governed type whose snapshots are not queried should still have its policy evaluated on its states`(): Unit =
        runBlocking {
            val engine = QueryEngine.Builder(MANIFEST)
                .snapshots(storage) { it == NOTE }
                .accessPolicies(listOf(documents), AccessFixtures::aggregatesOf)
                .build()
            engine.policies.map { it.aggregateType }.assert().containsExactlyInAnyOrder(DOCUMENT, LEDGER)
            val hr = state(record(DOCUMENT, "d-hr", mapOf("department" to "hr")))
            engine.reads.admitted(alice, hr).assert().isNull()
            engine.reads.admitted(caller("dave", "departments" to listOf("hr")), hr).assert().isNotNull()
            engine.reads.history(hr)["title"].stringValue().assert().isEqualTo("d-hr")
        }

    @Test
    fun `two callers whose claims differ should never share a count`(): Unit = runBlocking {
        val cache = QueryCountCache({ 1L })
        val engine = engine(documents, counts = cache)
        val gateway = engine.snapshots(DOCUMENT)!!
        val both = caller("alice", "departments" to listOf("sales", "hr"))
        gateway.count(CountQuery(MatchAllFilter), alice).count.assert().isEqualTo(1)
        gateway.count(CountQuery(MatchAllFilter), both).count.assert().isEqualTo(2)
        gateway.count(CountQuery(MatchAllFilter), alice).count.assert().isEqualTo(1)
        cache.size.assert().isEqualTo(2)
    }

    @Test
    fun `a governed dataset's rows should be read by their aggregate's current attributes`(): Unit = runBlocking {
        val rows = InMemoryDatasetStorage()
        fun row(id: String, document: String, tenant: String = "acme") =
            WowJson.mapper.createObjectNode().put("row_id", id).put("tenant_id", tenant).put("document_id", document)
        val binding = rows.put(
            "document_rows",
            listOf(
                row("r1", "d-sales"),
                row("r2", "d-sales"),
                row("r3", "d-hr"),
                // Of an aggregate the warehouse holds no attributes of.
                row("r4", "d-none"),
                row("r5", "d-sales", tenant = "other"),
            ),
        )
        // d-hr was a sales document; its newer commit moved it to hr.
        rows.putAccess(DOCUMENT, "acme", "d-hr", 1, mapOf("owner" to "zed", "department" to "sales"))
        rows.putAccess(DOCUMENT, "acme", "d-sales", 1, mapOf("owner" to "zed", "department" to "sales"))
        rows.putAccess(
            DOCUMENT,
            "acme",
            "d-hr",
            2,
            mapOf("owner" to "zed", "department" to "hr", "sharedWith" to listOf("bob"))
        )
        rows.putAccess(DOCUMENT, "other", "d-sales", 1, mapOf("owner" to "zed", "department" to "sales"))
        val dataset = QueryDataset.of(AggregateType("docs", "document_rows")) {
            tenant("tenant_id")
            identity("row_id")
            access("docs.document", "document_id")
            storage(binding)
            listOf("row_id", "tenant_id", "document_id").forEach { field(it) { types(QueryValueType.STRING) } }
        }
        val engine = QueryEngine.Builder(MANIFEST)
            .datasets(QueryDatasets(listOf(dataset)), rows)
            .accessPolicies(listOf(documents), AccessFixtures::aggregatesOf)
            .build()
        suspend fun ids(caller: QueryCaller): List<String> =
            engine.datasets[dataset.name]!!.list(ListQuery(MatchAllFilter, limit = 100), caller).toList()
                .map { it["row_id"].stringValue() }
        ids(alice).assert().containsExactlyInAnyOrder("r1", "r2")
        ids(caller("bob")).assert().containsExactly("r3")
        ids(caller("dan")).assert().isEmpty()
        // A grant of every aggregate reads no attributes, the row of d-none among them.
        ids(auditor).assert().containsExactlyInAnyOrder("r1", "r2", "r3", "r4")
        ids(QueryCaller(Caller.ofService("basket", "acme"))).assert().containsExactlyInAnyOrder("r1", "r2", "r3", "r4")
        ids(QueryCaller.IN_PROCESS).assert().hasSize(5)
    }

    @Test
    fun `a dataset bound to an aggregate the node does not have should be refused, now and as it joins`() {
        val orders = QueryDatasetTest.orders()
        fun bound(aggregate: AggregateType) = QueryDataset(
            orders.name,
            QueryDatasetScope.of(
                tenant = orders.scope.tenant,
                access = QueryDatasetAccess.of(aggregate, QueryField("order_id")),
            ),
            orders.declaration,
        )
        val missing = AggregateType("docs", "missing")
        fun builder(datasets: QueryDatasets) = QueryEngine.Builder(MANIFEST)
            .accessPolicies(listOf(documents), AccessFixtures::aggregatesOf)
            .datasets(datasets, null)
        assertThrows<IllegalArgumentException> { builder(QueryDatasets(listOf(bound(missing)))).build() }
            .message.assert().contains("is bound to the aggregate docs.missing, which is not an aggregate of this node")
        val datasets = QueryDatasets(listOf(orders))
        builder(datasets).build()
        assertThrows<IllegalArgumentException> { datasets.put(bound(missing)) }
        datasets.put(bound(DOCUMENT))
        datasets[orders.name]!!.scope.access!!.aggregate.assert().isEqualTo(DOCUMENT)
        datasets.remove(orders.name)
        datasets.all.assert().isEmpty()
    }

    @Test
    fun `a policy of a state type should govern the one aggregate the host gives for it`() {
        val engine = engine(documents)
        engine.access.of(DOCUMENT).assert().isSameAs(documents)
        engine.access.governs(NOTE).assert().isFalse()
        engine.access.declares(DOCUMENT, DEPARTMENT).assert().isTrue()
    }
}
