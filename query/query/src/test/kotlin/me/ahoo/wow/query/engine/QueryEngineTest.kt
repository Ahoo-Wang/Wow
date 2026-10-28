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

package me.ahoo.wow.query.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.query.events.UnpinnedEventsPolicy
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.policy.AbacQueryOptions
import me.ahoo.wow.query.policy.EventsMode
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.query.state.SnapshotPolicyGateway
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

/** The read side composed once (decision D237): which gateways the builder gives, and its fail-closed rules (R3.3-3). */
class QueryEngineTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")
    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                "shop",
                aggregates = listOf(aggregate("order"), aggregate("cart")),
            ),
        ),
    )

    private fun aggregate(name: String) = AggregateManifest(
        name,
        state = StateManifest(Manifest.schemaRef(name)),
        queries = listOf(QueryManifest(QueryRoutes.SNAPSHOT), QueryManifest(QueryRoutes.EVENTS)),
    )

    /** Only the orders' snapshots are queried; the carts' are not, as `snapshot = false` would leave them. */
    private fun builder() = QueryEngine.Builder(manifest).snapshots(InMemoryQueryStorage()) { it == order }

    private fun state(type: AggregateType, region: String) = CommittedState(
        ref = type.ref("acme", "x-1"),
        version = 1,
        scope = Scope("alice", null),
        deleted = false,
        tags = mapOf("region" to listOf(region)),
        state = WowJson.mapper.createObjectNode().put("items", 2),
    )

    private fun tagged(region: String) =
        QueryCaller(
            Caller(tenantId = "acme", scope = Scope("alice", null), attributes = mapOf("abac.region" to listOf(region)))
        )

    @Test
    fun `without ABAC or an application policy, the scope alone decides and no policy gateway is built`(): Unit =
        runBlocking {
            val engine = builder().build()
            engine.snapshots.map { it.aggregateType }.assert().containsExactly(order)
            engine.events.assert().isEmpty()
            engine.policies.assert().isEmpty()
            engine.visibility(cart).assert().isNull()
            engine.datasets.assert().isSameAs(DatasetQueryGateways.NONE)
            engine.reads.admitted(tagged("west"), state(cart, "east"))!!["items"].asInt().assert().isEqualTo(2)
        }

    @Test
    fun `with ABAC, a type whose snapshots are not queried fails closed through its policy gateway`(): Unit =
        runBlocking {
            val engine = builder().abac(AbacQueryOptions(enabled = true, matchMissingTagKey = false)).build()
            engine.policies.map { it.aggregateType }.assert().containsExactly(cart)
            engine.visibility(cart).assert().isInstanceOf(SnapshotPolicyGateway::class.java)
            engine.visibility(order).assert().isSameAs(engine.snapshots.single())
            engine.reads.admitted(tagged("west"), state(cart, "east")).assert().isNull()
            engine.reads.admitted(tagged("east"), state(cart, "east")).assert().isNotNull()
            engine.reads.admitted(tagged("west"), state(order, "east")).assert().isNull()
        }

    @Test
    fun `an application policy makes the policy gateways too, and the facade reads through them`(): Unit =
        runBlocking {
            val denying =
                QueryPolicy { caller, _ -> if (caller.subject == "admin") MatchAllFilter else MatchNoneFilter }
            val engine = builder().policies(listOf(denying)).build()
            engine.policies.map { it.aggregateType }.assert().containsExactly(cart)
            val alice = QueryCaller(Caller(subject = "alice", tenantId = "acme", scope = Scope("alice", null)))
            val admin = QueryCaller(Caller(subject = "admin", tenantId = "acme", scope = Scope("alice", null)))
            val cartState = state(cart, "east")
            engine.reads.read(alice, { cartState }, { it }).assert().isSameAs(ReadFacade.Read.Hidden)
            engine.reads.read(alice, {
                null
            }, { committed: CommittedState -> committed }).assert().isSameAs(ReadFacade.Read.Missing)
            val visible = engine.reads.read(admin, { cartState }, { it }) as ReadFacade.Read.Visible
            visible.view.assert().isSameAs(cartState)
            visible.state["items"].asInt().assert().isEqualTo(2)
        }

    @Test
    fun `the events of a type an events backend serves are queried, hidden as its snapshots are`(): Unit =
        runBlocking {
            val events = InMemoryEventQueryStorage({ emptyList() })
            val engine = builder()
                .contexts(setOf("shop"))
                .events { type -> events.binding(type).takeIf { type == cart } }
                .abac(AbacQueryOptions(enabled = true, matchMissingTagKey = false))
                .committedStates { ref -> state(ref.type, "east").copy(ref = ref) }
                .datasets(QueryDatasets.NONE, null)
                .build()
            engine.events.map { it.aggregateType }.assert().containsExactly(cart)
            engine.catalog.assert().isNotNull()
        }

    @Test
    fun `the snapshot and events queries of one store should share its bulkhead, whichever model asks`(): Unit =
        runBlocking {
            val bulkhead = QueryBulkhead("main", 1)
            val events = InMemoryEventQueryStorage({ emptyList() })
            val engine = QueryEngine.Builder(manifest)
                .snapshots(InMemoryQueryStorage())
                .events { type -> events.binding(type) }
                .bulkheads { _, model -> bulkhead.takeIf { model == QueryModel.SNAPSHOT || model == QueryModel.EVENTS } }
                .build()
            val caller = QueryCaller(Caller(tenantId = "acme"))
            val snapshots = engine.snapshots.first { it.aggregateType == order }
            val orderEvents = engine.events.first { it.aggregateType == order }
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val held = async(Dispatchers.Default) {
                bulkhead.run {
                    entered.complete(Unit)
                    gate.await()
                }
            }
            entered.await()
            listOf(
                runCatching { snapshots.list(ListQuery(MatchAllFilter, limit = 10), caller).toList() },
                runCatching { orderEvents.count(CountQuery(AggregateIdFilter("o-1")), caller) },
            ).forEach { refused ->
                (refused.exceptionOrNull() as QueryError).errorCode.assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
            }
            gate.complete(Unit)
            held.await()
            snapshots.list(ListQuery(MatchAllFilter, limit = 10), caller).toList().assert().isEmpty()
            orderEvents.count(CountQuery(AggregateIdFilter("o-1")), caller).count.assert().isZero()
        }

    @Test
    fun `an events query that names no aggregate should be refused where the type refuses it`(): Unit = runBlocking {
        val events = InMemoryEventQueryStorage({ emptyList() })
        val engine = builder()
            .events { type -> events.binding(type) }
            .unpinnedEvents { type -> if (type == cart) UnpinnedEvents.REFUSE else UnpinnedEvents.ALLOW }
            .build()
        val caller = QueryCaller(Caller(tenantId = "acme"))
        val carts = engine.events.first { it.aggregateType == cart }
        val orders = engine.events.first { it.aggregateType == order }
        val refused = runCatching { carts.list(ListQuery(MatchAllFilter, limit = 10), caller).toList() }
            .exceptionOrNull() as QueryError
        refused.errorCode.assert().isEqualTo(FrameworkErrors.QUERY_INVALID)
        refused.bindingErrors.single().code.assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
        refused.bindingErrors.single().name.assert().isEqualTo("aggregateId")
        carts.list(ListQuery(AggregateIdFilter("c-1"), limit = 10), caller).toList().assert().isEmpty()
        orders.list(ListQuery(MatchAllFilter, limit = 10), caller).toList().assert().isEmpty()
        val described = carts.describe(QueryEntry.HTTP)
        described.constraints.single { it.type == UnpinnedEventsPolicy.AGGREGATE_REQUIRED }
            .fields.assert().containsExactly("aggregateId")
        val allowed = orders.describe(QueryEntry.HTTP)
        allowed.constraints.none { it.type == UnpinnedEventsPolicy.AGGREGATE_REQUIRED }.assert().isTrue()
        described.version.assert().isNotEqualTo(allowed.version)
    }

    @Test
    fun `only a pinned policy pins the events queries, checked by the type's snapshots (D254, D259)`(): Unit =
        runBlocking {
            val events = InMemoryEventQueryStorage({ emptyList() })
            val pinned = mutableListOf<Pair<Int, Int>>()
            val observer = object : QueryObserver {
                override fun onEventsPinned(aggregateType: AggregateType, batched: Int, pointReads: Int) {
                    pinned += batched to pointReads
                }
            }

            fun policy(mode: EventsMode) = object : QueryPolicy {
                override val events: EventsMode = mode

                override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>) =
                    if (caller.subject == "admin") MatchAllFilter else MatchNoneFilter
            }

            fun orders(mode: EventsMode) = builder()
                .events { type -> events.binding(type) }
                .policies(listOf(policy(mode)))
                .observers(listOf(observer))
                .committedStates { ref -> state(ref.type, "east").copy(ref = ref).takeIf { ref.id == "o-1" } }
                .build()
                .events.first { it.aggregateType == order }
            val admin = QueryCaller(Caller(subject = "admin", tenantId = "acme"))
            val alice = QueryCaller(Caller(subject = "alice", tenantId = "acme"))
            listOf(EventsMode.PUSHDOWN, EventsMode.NONE).forEach { mode ->
                orders(mode).list(ListQuery(MatchAllFilter, limit = 10), alice).toList().assert().isEmpty()
                orders(mode).describe(QueryEntry.HTTP).constraints
                    .none { it.type == UnpinnedEventsPolicy.AGGREGATE_REQUIRED }.assert().isTrue()
            }
            val pinnedOrders = orders(EventsMode.PINNED)
            val refused = runCatching { pinnedOrders.list(ListQuery(MatchAllFilter, limit = 10), alice).toList() }
                .exceptionOrNull() as QueryError
            refused.bindingErrors.single().code.assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
            pinnedOrders.describe(QueryEntry.HTTP).constraints
                .single { it.type == UnpinnedEventsPolicy.AGGREGATE_REQUIRED }.fields.assert().containsExactly(
                    "aggregateId"
                )
            // No snapshot is stored yet: each named order is read on its own, the missing one showing nothing.
            val named = ListQuery(AggregateIdsFilter(listOf("o-1", "o-2")), limit = 10)
            pinnedOrders.list(named, admin).toList().assert().isEmpty()
            pinnedOrders.list(named, alice).toList().assert().isEmpty()
            pinned.assert().containsExactly(0 to 2, 0 to 2)
        }

    @Test
    fun `the facade with no policy reads every state as it is`(): Unit = runBlocking {
        val read = ReadFacade.NONE.read(QueryCaller.IN_PROCESS, { state(cart, "east") }, { it })
        (read as ReadFacade.Read.Visible).state["items"].asInt().assert().isEqualTo(2)
        ReadFacade.NONE.event(cart, "added", WowJson.mapper.createObjectNode()).isEmpty.assert().isTrue()
        ManifestTypes.hosted(manifest, setOf("shop")).assert().containsExactly(order, cart)
        ManifestTypes.hosted(manifest, emptySet()).assert().isEmpty()
        ManifestStateSchemas(manifest).declares(order).assert().isFalse()
        ManifestStateSchemas(manifest).declares(AggregateType("shop", "none")).assert().isFalse()
        // An aggregate without its state schema, and a dataset (DX gap G31), declare nothing; only the first is warned.
        val schemas = ManifestStateSchemas(manifest)
        runBlocking { schemas.load(QuerySchemaContext(order, QueryModel.SNAPSHOT)) }.assert().isEmpty()
        runBlocking { schemas.load(QuerySchemaContext(order, QueryModel.SNAPSHOT)) }.assert().isEmpty()
        runBlocking { schemas.load(QuerySchemaContext(AggregateType("shop", "sales"), QueryModel.DATASET)) }.assert()
            .isEmpty()
        runBlocking { schemas.load(QuerySchemaContext(AggregateType("crm", "leads"), QueryModel.DATASET)) }.assert()
            .isEmpty()
    }
}
