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

package me.ahoo.wow.query.read

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.api.read.DatasetName
import me.ahoo.wow.api.read.MinVersionNotReachedException
import me.ahoo.wow.api.read.dynamic
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryError
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.json.query.toSnapshot
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

/** The node's typed QueryApi (decisions D254 ②, D259): one engine, the caller's scope, the in-process budget. */
class InProcessQueryApiTest {
    data class CartState(val items: Int = 0)

    data class ItemAdded(val sku: String)

    private val cart = AggregateType("shop", "cart")
    private val order = AggregateType("shop", "order")
    private val manifest = Manifest(
        contexts = listOf(ContextManifest("shop", aggregates = listOf(aggregate("cart"), aggregate("order")))),
    )
    private val storage = InMemoryQueryStorage()
    private val alice = Caller.of(tenantId = "acme", scope = Scope("alice"), subject = "alice")
    private val operator = Operator.of("alice", "acme")

    private fun aggregate(name: String) = AggregateManifest(
        name,
        state = StateManifest(Manifest.schemaRef(name)),
        queries = listOf(QueryManifest(QueryRoutes.SNAPSHOT), QueryManifest(QueryRoutes.EVENTS)),
    )

    private fun save(tenant: String, id: String, owner: String, items: Int = 1) {
        storage.save(
            SnapshotRecord(
                ref = cart.ref(tenant, id),
                version = 2,
                schemaFingerprint = "f",
                state = """{"items":$items}""",
                scope = Scope(owner),
                firstEventTime = 10,
                lastEventTime = 20,
                tags = mapOf("region" to listOf("east")),
                operator = operator,
            ),
        )
    }

    private val streams = listOf(
        EventStreamRecord(
            ref = cart.ref("acme", "c-1"),
            version = 1,
            commandId = "cmd-1",
            requestId = "req-1",
            commandType = "shop.cart.add",
            commitTime = 30,
            events = listOf(
                EventRecord("item_added", 1, """{"sku":"s-1"}"""),
                EventRecord("retired", 1, """{"a":1}""")
            ),
            scope = Scope("alice"),
            operator = operator,
        ),
    )

    private fun api(
        defaultListSize: Int = InProcessQueryApi.DEFAULT_LIST_SIZE,
        latest: (me.ahoo.wow.api.aggregate.AggregateRef) -> CommittedState? = { null },
    ): InProcessQueryApi {
        val events = InMemoryEventQueryStorage({ type -> if (type == cart) streams else emptyList() })
        val engine = QueryEngine.Builder(manifest)
            .snapshots(storage) { it == cart }
            .events { type -> events.create(type).takeIf { type == cart } }
            // The host's default (`wow.query.budgets.in-process.*`): the HTTP defaults.
            .entryPolicy(QueryEntryPolicy(inProcess = QueryBudget.IN_PROCESS_DEFAULT))
            .build()
        return InProcessQueryApi(
            engine,
            ReadModels(
                listOf(ReadModels.Model(cart, CartState::class.java, mapOf("item_added" to ItemAdded::class.java)::get))
            ),
            latest = { latest(it) },
            defaultListSize = defaultListSize,
        )
    }

    private fun committed(ref: me.ahoo.wow.api.aggregate.AggregateRef, owner: String) = CommittedState(
        ref = ref,
        version = 3,
        scope = Scope(owner),
        deleted = false,
        tags = emptyMap(),
        state = WowJson.mapper.createObjectNode().put("items", 7),
    )

    @Test
    fun `a caller should read the typed snapshots of its own tenant and owner`(): Unit = runBlocking {
        save("acme", "c-1", "alice", items = 2)
        save("acme", "c-2", "bob")
        save("other", "c-3", "alice")
        val carts = api().of(alice).snapshots<CartState>()
        carts.type.assert().isEqualTo(cart)
        val snapshot = carts.single(SingleQuery(AggregateIdFilter("c-1")))!!
        snapshot.ref.assert().isEqualTo(cart.ref("acme", "c-1"))
        snapshot.version.assert().isEqualTo(2)
        snapshot.scope.assert().isEqualTo(Scope("alice"))
        snapshot.tags.assert().isEqualTo(mapOf("region" to listOf("east")))
        snapshot.deleted.assert().isFalse()
        snapshot.operator.assert().isEqualTo(operator)
        snapshot.firstEventTime.assert().isEqualTo(10)
        snapshot.lastEventTime.assert().isEqualTo(20)
        snapshot.state.assert().isEqualTo(CartState(2))
        carts.single(SingleQuery(AggregateIdFilter("c-2"))).assert().isNull()
        carts.list(ListQuery(MatchAllFilter)).toList().map { it.ref.id }.assert().containsExactly("c-1")
        carts.count(CountQuery(MatchAllFilter)).count.assert().isEqualTo(1)
        val page = carts.paged(PagedQuery(MatchAllFilter, pagination = Pagination(1, 10), total = TotalMode.BOUNDED))
        page.list.map { it.state }.assert().containsExactly(CartState(2))
        page.total.assert().isEqualTo(1)
        carts.cursor(CursorQuery(MatchAllFilter, size = 10)).list.single().ref.id.assert().isEqualTo("c-1")
    }

    @Test
    fun `trusted code should read every tenant, within the in-process budget unless unbounded`(): Unit = runBlocking {
        save("acme", "c-1", "alice")
        save("other", "c-2", "bob")
        val api = api()
        api.trusted().snapshots(cart).list(ListQuery(MatchAllFilter)).toList().map { it.state }
            .assert().containsExactly(CartState(1), CartState(1))
        val tooMany = ListQuery(MatchAllFilter, limit = 1001)
        val refused = runCatching { api.trusted().snapshots<CartState>().list(tooMany).toList() }
            .exceptionOrNull() as QueryError
        refused.errorCode.assert().isEqualTo(FrameworkErrors.QUERY_INVALID)
        api.trusted().unbounded().snapshots<CartState>().list(tooMany).toList().assert().hasSize(2)
        api.trusted().unbounded().snapshots<CartState>()
            .paged(PagedQuery(MatchAllFilter, total = TotalMode.EXACT)).exact.assert().isTrue()
    }

    @Test
    fun `a list that names no limit should run with the budget's list default`(): Unit = runBlocking {
        (1..3).forEach { save("acme", "c-$it", "alice") }
        val carts = api(defaultListSize = 2).of(alice).snapshots<CartState>()
        carts.list(ListQuery(MatchAllFilter)).toList().assert().hasSize(2)
        carts.list(ListQuery(MatchAllFilter, limit = 3)).toList().assert().hasSize(3)
        // No default: a list naming no limit asks for every match, which the budget refuses, as over HTTP.
        val refused = runCatching {
            api(
                defaultListSize = 0
            ).of(alice).snapshots<CartState>().list(ListQuery(MatchAllFilter)).toList()
        }
        (refused.exceptionOrNull() as QueryError).errorCode.assert().isEqualTo(FrameworkErrors.QUERY_INVALID)
        api(defaultListSize = 2).trusted().unbounded().snapshots<CartState>().list(ListQuery(MatchAllFilter))
            .toList().assert().hasSize(3)
    }

    @Test
    fun `a stream should resume after a position, bounded by neither the list size nor the default`(): Unit =
        runBlocking {
            (1..3).forEach { save("acme", "c-$it", "alice", items = it) }
            val carts = api(defaultListSize = 1).of(alice).snapshots<CartState>()
            val query = ListQuery(MatchAllFilter, sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC)))
            val all = carts.stream(query).toList()
            all.map { it.row.state.items }.assert().containsExactly(1, 2, 3)
            all.forEach { it.position.assert().isNotNull() }
            carts.stream(query, after = all.first().position).toList().map { it.row.ref.id }
                .assert().containsExactly("c-2", "c-3")
        }

    @Test
    fun `a record should decode its absent operator and owner, and refuse a missing field`() {
        save("acme", "c-1", "alice")
        val record = storage.records(cart).single().deepCopy().apply {
            putNull("operator")
            putNull("ownerId")
        }
        val snapshot = record.toSnapshot(cart, CartState::class.java)
        snapshot.operator.assert().isNull()
        snapshot.scope.assert().isEqualTo(Scope.NONE)
        record.remove("version")
        assertThrows<IllegalStateException> { record.toSnapshot(cart, CartState::class.java) }
            .message.assert().contains("version")
        record.put("version", 1).putNull("state")
        assertThrows<IllegalStateException> { record.toSnapshot(cart, CartState::class.java) }
            .message.assert().contains("state")
        record.set("state", storage.records(cart).single()["state"])
        record.remove("tags")
        record.toSnapshot(cart, CartState::class.java).tags.assert().isEmpty()
    }

    @Test
    fun `a typed query should refuse a projection, which dynamic rows take`(): Unit = runBlocking {
        save("acme", "c-1", "alice", items = 4)
        val carts = api().of(alice).snapshots<CartState>()
        val projected = SingleQuery(
            AggregateIdFilter("c-1"),
            projection = Projection(include = listOf(QueryField("aggregateId")))
        )
        assertThrows<IllegalArgumentException> { carts.single(projected) }
        val tree = carts.trees().single(projected)!!
        tree["aggregateId"].asString().assert().isEqualTo("c-1")
        tree.has("state").assert().isFalse()
        val map = carts.dynamic<Map<String, Any?>>().list(ListQuery(MatchAllFilter)).toList().single()
        map["aggregateId"].assert().isEqualTo("c-1")
        carts.dynamic<ObjectNode>().count(CountQuery(MatchAllFilter)).count.assert().isEqualTo(1)
    }

    @Test
    fun `an aggregation should decode its groups as the caller asks`(): Unit = runBlocking {
        save("acme", "c-1", "alice")
        val carts = api().of(alice).snapshots<CartState>()
        val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
        carts.groupTrees(query).toList().single()["count"].asInt().assert().isEqualTo(1)
        carts.aggregate(query, Map::class.java).toList().single()["count"].assert().isEqualTo(1L)
    }

    @Test
    fun `a state read should answer what the caller may see, and null for the rest alike`(): Unit = runBlocking {
        val api = api(
            latest = { ref -> if (ref.id == "missing") null else committed(ref, if (ref.id == "b") "bob" else "alice") }
        )
        val carts = api.of(alice).snapshots<CartState>()
        val state = carts.state("a")!!
        state.ref.assert().isEqualTo(cart.ref("acme", "a"))
        state.version.assert().isEqualTo(3)
        state.scope.assert().isEqualTo(Scope("alice"))
        state.deleted.assert().isFalse()
        state.tags.assert().isEmpty()
        state.state.assert().isEqualTo(CartState(7))
        carts.state("b").assert().isNull()
        carts.state("missing").assert().isNull()
        carts.state(cart.ref("other", "a")).assert().isNull()
        assertThrows<IllegalArgumentException> { carts.state(order.ref("acme", "a")) }
        assertThrows<IllegalStateException> { api.trusted().snapshots<CartState>().state("a") }
        api.trusted().snapshots<CartState>().state(cart.ref("other", "b"))!!.scope.assert().isEqualTo(Scope("bob"))
    }

    @Test
    fun `a state read of a minimum version fails only for a state the caller may see (Q-13, Q-5)`(): Unit =
        runBlocking {
            val api = api(
                latest = { ref ->
                    if (ref.id == "missing") null else committed(ref, if (ref.id == "b") "bob" else "alice")
                },
            )
            val carts = api.of(alice).snapshots<CartState>()
            carts.state("a", 3)!!.version.assert().isEqualTo(3)
            val behind = assertThrows<MinVersionNotReachedException> { carts.state("a", 4) }
            behind.version.assert().isEqualTo(3)
            behind.minVersion.assert().isEqualTo(4)
            // Hidden, and missing, answer as without a minimum version: null.
            carts.state("b", 4).assert().isNull()
            carts.state("missing", 1).assert().isNull()
            carts.state(cart.ref("acme", "a"), 3)!!.state.assert().isEqualTo(CartState(7))
            assertThrows<IllegalArgumentException> { carts.state("a", 0) }
            assertThrows<IllegalStateException> { api.trusted().snapshots<CartState>().state("a", 1) }
            MinVersionNotReachedException.CODE.assert().isEqualTo(QueryErrors.MIN_VERSION_NOT_REACHED)
        }

    @Test
    fun `a handler's queries should read the whole tenant of its commit, and others only trusted`(): Unit =
        runBlocking {
            save("acme", "c-1", "alice")
            save("acme", "c-2", "bob")
            save("other", "c-3", "carol")
            val api = api(latest = { ref -> committed(ref, "bob") })
            val queries = api.tenant("acme")
            queries.tenantId.assert().isEqualTo("acme")
            queries.snapshots<CartState>().list(ListQuery(MatchAllFilter)).toList().map { it.ref.id }
                .assert().containsExactlyInAnyOrder("c-1", "c-2")
            queries.snapshots(cart).count(CountQuery(MatchAllFilter)).count.assert().isEqualTo(2)
            queries.snapshots<CartState>().state("c-2")!!.ref.assert().isEqualTo(cart.ref("acme", "c-2"))
            queries.snapshots<CartState>().state(cart.ref("other", "c-3")).assert().isNull()
            queries.events(cart).list(ListQuery(AggregateIdFilter("c-1"))).toList().assert().hasSize(1)
            queries.trusted().snapshots<CartState>().list(ListQuery(MatchAllFilter)).toList().assert().hasSize(3)
        }

    @Test
    fun `the events of a type should be typed by the model's event classes`(): Unit = runBlocking {
        val events = api().of(alice).events(cart)
        events.type.assert().isEqualTo(cart)
        val stream = events.list(ListQuery(AggregateIdFilter("c-1"))).toList().single()
        stream.ref.assert().isEqualTo(cart.ref("acme", "c-1"))
        stream.version.assert().isEqualTo(1)
        stream.commitTime.assert().isEqualTo(30)
        stream.commandId.assert().isEqualTo("cmd-1")
        stream.requestId.assert().isEqualTo("req-1")
        stream.commandType.assert().isEqualTo("shop.cart.add")
        stream.scope.assert().isEqualTo(Scope("alice"))
        stream.operator.assert().isEqualTo(operator)
        stream.events.map { it.name }.assert().containsExactly("item_added", "retired")
        stream.events[0].body.assert().isEqualTo(ItemAdded("s-1"))
        stream.events[0].revision.assert().isEqualTo(1)
        stream.events[1].body.assert().isEqualTo(mapOf("a" to 1))
        events.trees().single(SingleQuery(AggregateIdFilter("c-1")))!!["commandId"].asString()
            .assert().isEqualTo("cmd-1")
    }

    @OptIn(ExperimentalWowApi::class)
    @Test
    fun `a model the node does not serve should be refused when it is asked for`() {
        val api = api()
        assertThrows<IllegalArgumentException> { api.of(alice).snapshots(order) }
        assertThrows<IllegalArgumentException> { api.of(alice).events(order) }
        assertThrows<IllegalArgumentException> { api.of(alice).snapshots<ItemAdded>() }
        assertThrows<IllegalArgumentException> { api.of(alice).datasetTrees(DatasetName.of("shop", "sales")) }
    }

    @Test
    fun `the read models should find a type by its state, once`() {
        val models = ReadModels(
            listOf(ReadModels.Model(cart, CartState::class.java), ReadModels.Model(order, CartState::class.java)),
        )
        assertThrows<IllegalArgumentException> { models.typeOf(CartState::class.java) }
        models.stateTypeOf(order).assert().isEqualTo(CartState::class.java)
        models.eventTypeOf(order, "x").assert().isNull()
        ReadModels.NONE.stateTypeOf(cart).assert().isNull()
        assertThrows<IllegalArgumentException> {
            ReadModels(
                listOf(ReadModels.Model(cart, CartState::class.java), ReadModels.Model(cart, ItemAdded::class.java))
            )
        }
        assertThrows<IllegalArgumentException> {
            InProcessQueryApi(
                QueryEngine.Builder(manifest).build(),
                models,
                defaultListSize = -1
            )
        }
        assertThrows<IllegalArgumentException> {
            InProcessQueryApi(
                QueryEngine.Builder(manifest).build(),
                models,
                chunkSize = 0
            )
        }
    }
}
