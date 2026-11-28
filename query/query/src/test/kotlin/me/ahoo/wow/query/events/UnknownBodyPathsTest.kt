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

package me.ahoo.wow.query.events

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.filter.walkFilterNodes
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/**
 * A body path of the `events` model means that path in the events that declare it, at a revision this node knows
 * (#7235): through the gateway, as a caller asks, nothing of an event the Manifest does not know, or of one at a
 * revision newer than it knows, is compared, grouped, expanded or counted by its body, which a masked read answers as
 * `null`. The rule's cases on every backend are the contract's (`EventQueryBackendSpec`, `EventAnalyticsCases`).
 */
class UnknownBodyPathsTest {
    private val type = AggregateType("shop", "cart")
    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                "shop",
                aggregates = listOf(
                    AggregateManifest(
                        "cart",
                        state = StateManifest(Manifest.schemaRef("Cart")),
                        events = listOf(
                            EventManifest("Noted", schema = Manifest.schemaRef("Noted")),
                            EventManifest("Packed", schema = Manifest.schemaRef("Packed")),
                        ),
                        retired = listOf("Legacy"),
                    ),
                ),
            ),
        ),
        defs = mapOf(
            "Cart" to mapOf("type" to "object"),
            "Noted" to mapOf(
                "type" to "object",
                "properties" to mapOf("owner" to mapOf("type" to "string"), "amount" to mapOf("type" to "integer")),
            ),
            "Packed" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "lines" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "object", "properties" to mapOf("sku" to mapOf("type" to "string"))),
                    ),
                ),
            ),
        ),
    )

    private fun stream(id: String, events: List<EventRecord>, of: AggregateType = type) = EventStreamRecord(
        ref = of.ref(TENANT, id),
        version = 1,
        commandId = "cmd-$id",
        requestId = "req-$id",
        commandType = "${of.context}.${of.name}.Note",
        commitTime = 1000L,
        events = events,
        scope = Scope("alice", "s-1"),
        access = AccessValues.ofStream(emptyMap(), Scope("alice", "s-1")),
    )

    private val streams = listOf(
        stream("known", listOf(EventRecord("shop.cart.Noted", 1, """{"owner":"mona","amount":1}"""))),
        stream("unknown", listOf(EventRecord("shop.cart.Renamed", 1, """{"owner":"$SECRET","amount":5}"""))),
        stream("newer", listOf(EventRecord("shop.cart.Noted", 9, """{"owner":"$SECRET2","amount":7}"""))),
        stream("packed", listOf(EventRecord("shop.cart.Packed", 1, """{"lines":[{"sku":"pear"}]}"""))),
        stream("repacked", listOf(EventRecord("shop.cart.Repacked", 1, """{"lines":[{"sku":"$SECRET"}]}"""))),
        stream("newly-packed", listOf(EventRecord("shop.cart.Packed", 9, """{"lines":[{"sku":"$SECRET2"}]}"""))),
    )

    /** The admitted queries the backend is asked, most recent last. */
    private val admitted = ArrayList<AdmittedQuery<*>>()

    private fun gateway(of: AggregateType = type, entryPolicy: QueryEntryPolicy = QueryEntryPolicy()): QueryGateway {
        val storage = InMemoryEventQueryStorage({ asked -> (streams + ghosts).filter { it.ref.type == asked } })
        val binding = storage.binding(of)
        val backend = object : QueryBackend by binding.backend {
            override suspend fun count(query: AdmittedQuery<CountQuery>): Long {
                admitted += query
                return binding.backend.count(query)
            }
        }
        return EventQueryGateways.create(
            of,
            backend,
            QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
                .compile(QuerySchemaContext(of, QueryModel.EVENTS), binding.storage),
            entryPolicy = entryPolicy,
            masks = PayloadMasks(manifest),
        )
    }

    private val ghosts = listOf(stream("ghost", listOf(EventRecord("shop.ghost.Haunted", 1, """{"x":1}""")), GHOST))

    private val caller = QueryCaller(Caller(tenantId = TENANT), entry = QueryEntry.HTTP)

    private fun ids(filter: String, gateway: QueryGateway = gateway()): List<String> = runBlocking {
        gateway.list(QueryJson.mapper.readValue("""{"filter":$filter,"limit":10}""", ListQuery::class.java), caller)
            .toList().map { it["aggregateId"].asString() }
    }

    private fun aggregate(query: String): String = runBlocking {
        gateway().aggregate(QueryJson.mapper.readValue(query, AggregationQuery::class.java), caller).toList().toString()
    }

    @Test
    fun `a body condition should not match the stored body of an event the Manifest does not know`() {
        listOf(SECRET, SECRET2).forEach { secret ->
            ids(owner("eq", secret)).assert().describedAs(secret).isEmpty()
        }
        ids(owner("eq", "mona")).assert().containsExactly("known")
    }

    @Test
    fun `an elements aggregation should not answer the stored body of an event the Manifest does not know`() {
        val rows = aggregate(
            """{"filter":{"op":"match-all"},"elements":[{"path":"events"}],""" +
                """"groupBy":[{"type":"terms","field":"body.owner","alias":"o"}],""" +
                """"metrics":[{"type":"count","alias":"n"}]}""",
        )
        rows.assert().doesNotContain(SECRET).doesNotContain(SECRET2).isEqualTo("""[{"o":"mona","n":1}]""")
    }

    @Test
    fun `an expansion below a body array expands it only in the events that declare it`() {
        val rows = aggregate(
            """{"filter":{"op":"match-all"},"elements":[{"path":"events"},{"path":"body.lines"}],""" +
                """"groupBy":[{"type":"terms","field":"sku","alias":"sku"}],"metrics":[{"type":"count","alias":"n"}]}""",
        )
        rows.assert().isEqualTo("""[{"sku":"pear","n":1}]""")
        listOf(SECRET, SECRET2).forEach { secret ->
            val lines = """{"op":"element-match","field":"body.lines","predicate":""" +
                """{"op":"eq","field":"sku","value":"$secret"}}"""
            ids("""{"op":"element-match","field":"events","predicate":$lines}""").assert().describedAs(secret).isEmpty()
        }
    }

    @Test
    fun `an expression of body paths is computed only on the events that declare them`() {
        val amount = AggregationExpression.Field(QueryField("body.amount"))
        val query = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("events"))),
            metrics = listOf(
                AggregationMetric.Count("events"),
                AggregationMetric.Count("positive", ExpressionFilter(amount, ComparisonOperator.GT, 0.0)),
                AggregationMetric.Numeric(AggregationFunction.SUM, amount, "amount"),
            ),
        )
        val row = runBlocking { gateway().aggregate(query, caller).toList() }.single()
        row["events"].asLong().assert().isEqualTo(6)
        row["positive"].asLong().assert().isEqualTo(1)
        row["amount"].asDouble().assert().isEqualTo(1.0)
    }

    @Test
    fun `every metric and group of a body path reads only the events that declare it`() {
        val owner = QueryField("body.owner")
        val amount = AggregationExpression.Field(QueryField("body.amount"))
        val query = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("events"))),
            groupBy = listOf(AggregationGroup.Histogram(alias = "band", interval = 10.0, expression = amount)),
            metrics = listOf(
                AggregationMetric.Count("n"),
                AggregationMetric.Any(owner, "any"),
                AggregationMetric.DistinctCount(AggregationExpression.Field(owner), "owners"),
                AggregationMetric.Percentile(amount, 50.0, "median"),
                AggregationMetric.First(owner, "first", QueryField("body.amount")),
                AggregationMetric.Last(owner, "last", QueryField("body.amount")),
                AggregationMetric.Derived("again", DerivedExpression.MetricRef("n")),
                // A metric of the event's own fields reads every event: the revision is no path of its body.
                AggregationMetric.Count("upgraded", ExpressionFilter(revision, ComparisonOperator.GT, 1.0)),
            ),
        )
        val rows = runBlocking { gateway().aggregate(query, caller).toList() }
        rows.toString().assert().doesNotContain(SECRET).doesNotContain(SECRET2)
        val row = rows.single()
        row["band"].asDouble().assert().isEqualTo(0.0)
        row["n"].asLong().assert().isEqualTo(1)
        row["any"].asString().assert().isEqualTo("mona")
        row["owners"].asLong().assert().isEqualTo(1)
        row["median"].asDouble().assert().isEqualTo(1.0)
        row["first"].asString().assert().isEqualTo("mona")
        row["last"].asString().assert().isEqualTo("mona")
        row["again"].asLong().assert().isEqualTo(1)
        row["upgraded"].asLong().assert().isZero()
    }

    @Test
    fun `a body condition under an or and a nor reads the path as the masked read shows it`() {
        val packed = """{"op":"eq","field":"name","value":"shop.cart.Packed"}"""
        val secret = """{"op":"eq","field":"body.owner","value":"$SECRET"}"""
        fun events(predicate: String) = """{"op":"element-match","field":"events","predicate":$predicate}"""
        ids(events("""{"op":"or","operands":[$secret,$packed]}""")).assert()
            .containsExactlyInAnyOrder("packed", "newly-packed")
        ids(events("""{"op":"nor","operands":[$secret]}""")).assert().hasSize(streams.size)
        // The body itself is no path of it: every stored event has one.
        ids(events("""{"op":"exists","field":"body"}""")).assert().hasSize(streams.size)
    }

    @Test
    fun `a search of body paths reads them only on the events that declare them`() {
        // A store that searches text (Elasticsearch) is the one that admits it: here, a reference store granting it.
        val binding = InMemoryEventQueryStorage({ streams }).binding(type)
        val searching = object : QueryStorageAdapter {
            override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts {
                val facts = binding.storage.facts(logicalSchema)
                return QueryStorageFacts(
                    bindings = facts.bindings.mapValues { (_, native) ->
                        val exact = native.bindings[QueryCapability.EXACT_MATCH] ?: return@mapValues native
                        QueryValueBindings(
                            native.bindings + (QueryCapability.FULL_TEXT_TERMS to exact),
                            native.projectionPath,
                            native.responsePath,
                        )
                    },
                    storage = facts.storage,
                )
            }
        }
        val schema = QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
            .compile(QuerySchemaContext(type, QueryModel.EVENTS), searching).let { runBlocking { it.schema() } }
        val search = ElementMatchFilter(QueryField("events"), SearchFilter("mona", setOf(QueryField("body.owner"))))
        val admitted = QueryAdmission.Trusted.list(ListQuery(search, limit = 1), schema)
        val predicate = (admitted.query.filter as ElementMatchFilter).predicate
        val nodes = listOf(predicate).walkFilterNodes().toList()
        nodes.filterIsInstance<InFilter>().single().values.assert().containsExactly("shop.cart.Noted")
        nodes.filterIsInstance<LessThanOrEqualFilter>().single().value.assert().isEqualTo(1)
        nodes.filterIsInstance<SearchFilter>().single().query.assert().isEqualTo("mona")
        // A search of the event's own fields reads every event.
        val named = ElementMatchFilter(QueryField("events"), SearchFilter("Noted", setOf(QueryField("name"))))
        val unguarded = QueryAdmission.Trusted.list(ListQuery(named, limit = 1), schema).query.filter
        listOf((unguarded as ElementMatchFilter).predicate).walkFilterNodes().map { it.operator }.toList().assert()
            .doesNotContain(FilterOperator.IN, FilterOperator.LTE)
    }

    @Test
    fun `a body path of an aggregate the Manifest does not hold is refused`() {
        // Its events' bodies are of no known shape, so no event declares a path: none is a field of the model.
        val error = runCatching { ids(exists("body.x"), gateway(GHOST)) }.exceptionOrNull() as QueryError
        error.violations.single().code.assert().isEqualTo(QueryErrorCodes.UNKNOWN_FIELD)
        ids(exists("name"), gateway(GHOST)).assert().containsExactly("ghost")
    }

    @Test
    fun `the guards admission adds are counted against no caller's budget`() {
        // A caller's condition on a body path costs what one on the event's name costs: the guard is not the caller's.
        fun budgeted(filter: String, nodes: Int) = runBlocking {
            val policy = QueryEntryPolicy(
                http = QueryBudget(QueryBudget.HTTP_LABEL, maxFilterNodes = nodes, allowExpensiveOperators = false),
            )
            gateway(entryPolicy = policy).count(
                QueryJson.mapper.readValue("""{"filter":$filter}""", CountQuery::class.java),
                caller,
            )
        }

        fun fits(filter: String): Int = (1..MAX_NODES).first { runCatching { budgeted(filter, it) }.isSuccess }
        val named = """{"op":"element-match","field":"events","predicate":""" +
            """{"op":"eq","field":"name","value":"shop.cart.Noted"}}"""
        val budget = fits(named)
        fits(owner("eq", "mona")).assert().isEqualTo(budget)
        (runCatching { budgeted(owner("eq", "mona"), budget - 1) }.exceptionOrNull() as QueryError)
            .violations.single().code.assert().isEqualTo(QueryErrorCodes.FILTER_TOO_LARGE)
        // What the backend is asked holds the retired guard (an expensive `not-in`) and the body's guard besides.
        budgeted(owner("eq", "mona"), budget).count.assert().isEqualTo(1)
        val nodes = listOf(admitted.last().query.let { it as CountQuery }.filter).walkFilterNodes().toList()
        nodes.size.assert().isGreaterThan(budget)
        nodes.map { it.operator }.assert().contains(FilterOperator.NOT_IN, FilterOperator.IN, FilterOperator.LTE)
    }

    private fun owner(op: String, value: String) =
        """{"op":"element-match","field":"events","predicate":{"op":"$op","field":"body.owner","value":"$value"}}"""

    private fun exists(field: String) =
        """{"op":"element-match","field":"events","predicate":{"op":"exists","field":"$field"}}"""

    private companion object {
        const val TENANT = "t-1"
        const val SECRET = "s3cr3t-unknown-name"
        const val SECRET2 = "s3cr3t-newer-revision"
        const val MAX_NODES = 16
        val GHOST = AggregateType("shop", "ghost")
        val revision = AggregationExpression.Field(QueryField("revision"))
    }
}
