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

package me.ahoo.wow.query.tck

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.query.QueryTimeoutException
import me.ahoo.wow.query.aggregate
import me.ahoo.wow.query.countBounded
import me.ahoo.wow.query.cursor
import me.ahoo.wow.query.events.EventQueryGateways
import me.ahoo.wow.query.list
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.paged
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.UUID

/**
 * The contract of an `events` query backend (decision D235): every events backend passes it. A backend's test extends
 * it, stores the streams each test gives in [store], and supplies its binding of an aggregate type in [binding].
 *
 * A record is one stream ([me.ahoo.wow.query.events.EventStreamRecords.stored]); the schema is compiled as the Catalog
 * compiles it, from the test [manifest] ([EventsQuerySchemas]) and the backend's storage facts. Each test runs on its
 * own aggregate type (a fresh context). Queries are admitted through [QueryAdmission.Trusted] and run through the
 * core's query shapes, except the gateway tests, which run the whole admission and read each record as the model
 * shows it.
 *
 * The streams ([streams]): `o1` and `o2` of [TENANT], `o3` of [OTHER_TENANT]. `o1` v2 commits two `ItemAdded`; `o2` v2
 * one stored under its alias `LineAdded`; `o1` v3 an `OrderPaid` stored at revision 1 (the Manifest's is 2, so its
 * body paths are not filtered); `o1` v4 only the retired `LegacyNoted`.
 */
@Suppress("LargeClass")
abstract class EventQueryBackendSpec {
    /** Stores [streams], all of [type], where the backend under test reads them. */
    protected abstract suspend fun store(streams: List<EventStreamRecord>)

    /** The events query backend of [type], with its storage adapter. */
    protected abstract fun binding(type: AggregateType): QueryBackendBinding<QueryBackend>

    /** The aggregate type of this test: a fresh context, so each test has its own log. */
    protected val type: AggregateType =
        AggregateType("tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX), "order")

    private val context = QuerySchemaContext(type, QueryModel.EVENTS)

    /** The test Manifest: the order's events with their schemas, `OrderPaid` at revision 2, `LegacyNoted` retired. */
    protected val manifest: Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                type.context,
                aggregates = listOf(
                    AggregateManifest(
                        type.name,
                        state = StateManifest("#/\$defs/tck.OrderState"),
                        events = listOf(
                            EventManifest("OrderCreated", schema = "#/\$defs/tck.OrderCreated"),
                            EventManifest(
                                "ItemAdded",
                                aliases = listOf("LineAdded"),
                                schema = "#/\$defs/tck.ItemAdded"
                            ),
                            EventManifest("OrderPaid", revision = 2, schema = "#/\$defs/tck.OrderPaid"),
                        ),
                        retired = listOf("LegacyNoted"),
                    ),
                ),
            ),
        ),
        defs = mapOf(
            "tck.OrderState" to mapOf("type" to "object"),
            "tck.OrderCreated" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "customerId" to mapOf(
                        "type" to "string",
                        "x-wow-sensitive" to mapOf("level" to "DISPLAY"),
                        "x-wow-mask" to mapOf("keepPrefix" to 1),
                    ),
                    "amount" to mapOf("type" to "integer"),
                ),
            ),
            "tck.ItemAdded" to mapOf(
                "type" to "object",
                "properties" to mapOf("sku" to mapOf("type" to "string"), "qty" to mapOf("type" to "integer")),
            ),
            "tck.OrderPaid" to mapOf("type" to "object", "properties" to mapOf("amount" to mapOf("type" to "integer"))),
        ),
    )

    private val schemas = EventsQuerySchemas(manifest)

    /** The backend under test and the schema compiled over its facts, once the streams are stored. */
    protected class Fixture(val backend: QueryBackend, val schema: QueryModelSchema) {
        suspend fun list(query: ListQuery): List<ObjectNode> =
            backend.list(QueryAdmission.Trusted.list(query, schema)).toList()

        suspend fun keys(filter: FilterExpression, sort: List<Sort> = emptyList()): List<String> =
            list(ListQuery(filter, sort = sort, limit = MAX_LIST)).map { it.key() }.sorted()

        suspend fun count(filter: FilterExpression): Long =
            checkNotNull(backend.countBounded(QueryAdmission.Trusted.count(CountQuery(filter), schema)).count)

        suspend fun aggregate(query: AggregationQuery): List<ObjectNode> =
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
    }

    protected suspend fun given(streams: List<EventStreamRecord> = streams()): Fixture {
        store(streams)
        val binding = binding(type)
        val provider = QueryModelCompiler.of(events = schemas).compile(context, binding.storage)
        return Fixture(binding.backend, provider.schema())
    }

    /** One stream of this test's [type]. */
    @Suppress("LongParameterList")
    protected fun stream(
        id: String,
        version: Int,
        commitTime: Long,
        events: List<EventRecord>,
        tenantId: String = TENANT,
        scope: Scope = Scope("owner-1"),
        operator: Operator? = null,
    ) = EventStreamRecord(
        ref = type.ref(tenantId, id),
        version = version,
        commandId = "$id-command-$version",
        requestId = "$id-request-$version",
        commandType = "${type.context}.${type.name}.Command",
        commitTime = commitTime,
        events = events,
        scope = scope,
        operator = operator,
    )

    /** An event of this test's [type] named [name] (prefixed as the log stores it). */
    protected fun event(name: String, body: String, revision: Int = 1) =
        EventRecord("${type.context}.${type.name}.$name", revision, body)

    /** The streams most tests read (see the class). */
    protected fun streams(): List<EventStreamRecord> = listOf(
        stream(
            "o1",
            1,
            T0,
            listOf(event("OrderCreated", """{"customerId":"c-one","amount":10}""")),
            operator = Operator("user-1", TENANT, false),
        ),
        stream(
            "o1",
            2,
            T0 + 2,
            listOf(event("ItemAdded", """{"sku":"A","qty":2}"""), event("ItemAdded", """{"sku":"B","qty":3}"""))
        ),
        stream("o1", 3, T0 + 3, listOf(event("OrderPaid", """{"total":10}"""))),
        stream("o1", 4, T0 + 4, listOf(event("LegacyNoted", """{"note":"x"}"""))),
        stream(
            "o2",
            1,
            T0 + 1,
            listOf(event("OrderCreated", """{"customerId":"c-two","amount":20}""")),
            scope = Scope("owner-2")
        ),
        stream("o2", 2, T0 + 5, listOf(event("LineAdded", """{"sku":"A","qty":1}""")), scope = Scope("owner-2")),
        stream(
            "o3",
            1,
            T0 + 1,
            listOf(event("OrderCreated", """{"customerId":"c-three","amount":30}""")),
            tenantId = OTHER_TENANT,
        ),
    )

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    private fun name(local: String) = EqualFilter(QueryField("name"), "${type.context}.${type.name}.$local")

    private fun events(predicate: FilterExpression) = ElementMatchFilter(EVENTS, predicate)

    private fun rejected(code: String, block: suspend () -> Any?) = blocking {
        val error = runCatching { block() }.exceptionOrNull()
        (error is QuerySchemaValidationException).assert().describedAs("$error").isTrue()
        (error as QuerySchemaValidationException).violation?.code.assert().isEqualTo(code)
    }

    // Records and system fields

    @Test
    fun `a record is one stream with its system fields and its events as stored`() = blocking {
        val fixture = given()
        val record = fixture.list(
            ListQuery(AndFilter(listOf(AggregateIdFilter("o1"), EqualFilter(VERSION, 1))))
        ).single()
        record.path("aggregateId").stringValue().assert().isEqualTo("o1")
        record.path("tenantId").stringValue().assert().isEqualTo(TENANT)
        record.path("version").intValue().assert().isEqualTo(1)
        record.path("commitTime").longValue().assert().isEqualTo(T0)
        record.path("commandId").stringValue().assert().isEqualTo("o1-command-1")
        record.path("requestId").stringValue().assert().isEqualTo("o1-request-1")
        record.path("commandType").stringValue().assert().isEqualTo("${type.context}.${type.name}.Command")
        record.path("ownerId").stringValue().assert().isEqualTo("owner-1")
        record.path("spaceId").isNull.assert().isTrue()
        record.path("operator").path("id").stringValue().assert().isEqualTo("user-1")
        record.path("operator").path("crossScope").booleanValue().assert().isFalse()
        val event = record.path("events").single()
        event.path("name").stringValue().assert().isEqualTo("${type.context}.${type.name}.OrderCreated")
        event.path("revision").intValue().assert().isEqualTo(1)
        event.path("body").path("customerId").stringValue().assert().isEqualTo("c-one")
        Unit
    }

    @Test
    fun `system fields filter by aggregate, tenant, owner, version, commit time and operator`() = blocking {
        val fixture = given()
        fixture.keys(AggregateIdFilter("o2")).assert().containsExactly("o2@1", "o2@2")
        fixture.keys(TenantIdFilter(OTHER_TENANT)).assert().containsExactly("o3@1")
        fixture.keys(OwnerIdFilter("owner-2")).assert().containsExactly("o2@1", "o2@2")
        fixture.keys(GreaterThanOrEqualFilter(VERSION, 3)).assert().containsExactly("o1@3", "o1@4")
        fixture.keys(GreaterThanOrEqualFilter(COMMIT_TIME, T0 + 4)).assert().containsExactly("o1@4", "o2@2")
        fixture.keys(EqualFilter(QueryField("operator.id"), "user-1")).assert().containsExactly("o1@1")
        fixture.keys(EqualFilter(QueryField("requestId"), "o2-request-2")).assert().containsExactly("o2@2")
        Unit
    }

    // Event conditions are element predicates over the array

    @Test
    fun `an element match on the event name selects the commits that hold one, aliases included`() = blocking {
        val fixture = given()
        fixture.keys(events(name("ItemAdded"))).assert().containsExactly("o1@2", "o2@2")
        fixture.count(events(name("ItemAdded"))).assert().isEqualTo(2L)
        Unit
    }

    @Test
    fun `an element match never matches a retired event`() = blocking {
        val fixture = given()
        fixture.keys(events(EqualFilter(QueryField("revision"), 1)))
            .assert().containsExactly("o1@1", "o1@2", "o1@3", "o2@1", "o2@2", "o3@1")
        Unit
    }

    @Test
    fun `an element match combines the name and a revision-stable body field on one event`() = blocking {
        val fixture = given()
        fixture.keys(events(AndFilter(listOf(name("ItemAdded"), GreaterThanOrEqualFilter(QueryField("body.qty"), 2)))))
            .assert().containsExactly("o1@2")
        fixture.keys(events(EqualFilter(QueryField("body.sku"), "A"))).assert().containsExactly("o1@2", "o2@2")
        Unit
    }

    @Test
    fun `a body field an upgraded event declares is not filtered`() {
        rejected(QueryErrorCodes.UNSUPPORTED_CAPABILITY) {
            given().list(ListQuery(events(EqualFilter(QueryField("body.amount"), 10))))
        }
    }

    @Test
    fun `an event field outside its element scope is rejected`() {
        rejected(QueryErrorCodes.ELEMENT_SCOPE_REQUIRED) {
            given().list(ListQuery(EqualFilter(QueryField("events.name"), "x")))
        }
    }

    // Sorts, pages, cursors and counts: the unit is the commit

    @Test
    fun `list sorts by the commit time and honors the limit`() = blocking {
        val fixture = given()
        fixture.list(ListQuery(MatchAllFilter, sort = listOf(Sort(COMMIT_TIME, Sort.Direction.DESC)), limit = 3))
            .map { it.key() }.assert().containsExactly("o2@2", "o1@4", "o1@3")
        Unit
    }

    @Test
    fun `an unsorted list of one aggregate comes in version order`() = blocking {
        val fixture = given()
        fixture.list(ListQuery(AggregateIdFilter("o1"), limit = MAX_LIST)).map { it.key() }
            .assert().containsExactly("o1@1", "o1@2", "o1@3", "o1@4")
        Unit
    }

    @Test
    fun `a sort by an event field is rejected`() {
        rejected(QueryErrorCodes.ELEMENT_SCOPE_REQUIRED) {
            given().list(
                ListQuery(MatchAllFilter, sort = listOf(Sort(QueryField("events.body.qty"), Sort.Direction.ASC)))
            )
        }
    }

    @Test
    fun `paged pages commits and counts them`() = blocking {
        val fixture = given()
        val query = PagedQuery(
            TenantIdFilter(TENANT),
            sort = listOf(Sort(COMMIT_TIME, Sort.Direction.ASC)),
            pagination = Pagination(2, 2),
            total = TotalMode.EXACT,
        )
        val page = fixture.backend.paged(QueryAdmission.Trusted.paged(query, fixture.schema))
        page.list.map { it.key() }.assert().containsExactly("o1@2", "o1@3")
        page.total.assert().isEqualTo(6L)
        fixture.count(MatchAllFilter).assert().isEqualTo(7L)
        Unit
    }

    @Test
    fun `a cursor traverses every commit once by tenant, aggregate and version`() = blocking {
        val fixture = given()
        Assumptions.assumeTrue(fixture.schema.storage.paging.keyset != SupportMode.NONE)
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val admitted = QueryAdmission.Trusted.cursor(
                CursorQuery(MatchAllFilter, size = 2, cursor = cursor),
                fixture.schema
            )
            val page = fixture.backend.cursor(admitted)
            seen += page.list.map { "${it.path("tenantId").stringValue()}/${it.key()}" }
            cursor = page.nextCursor
        } while (cursor != null)
        seen.assert().containsExactly(
            "$OTHER_TENANT/o3@1",
            "$TENANT/o1@1",
            "$TENANT/o1@2",
            "$TENANT/o1@3",
            "$TENANT/o1@4",
            "$TENANT/o2@1",
            "$TENANT/o2@2",
        )
        Unit
    }

    @Test
    fun `a projection drops or keeps the events as a whole`() = blocking {
        val fixture = given()
        val record = fixture.list(
            ListQuery(AggregateIdFilter("o2"), projection = Projection(exclude = listOf(EVENTS)), limit = MAX_LIST),
        ).first()
        record.has("events").assert().isFalse()
        record.path("aggregateId").stringValue().assert().isEqualTo("o2")
        Unit
    }

    @Test
    fun `a projection inside the events is rejected`() {
        rejected(QueryErrorCodes.STORAGE_UNSUPPORTED) {
            given().list(
                ListQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("events.name"))))
            )
        }
    }

    // Per-event analytics expand the events

    @Test
    fun `an aggregation counts commits at the root`() = blocking {
        val fixture = given()
        val row = fixture.aggregate(
            AggregationQuery(TenantIdFilter(TENANT), metrics = listOf(AggregationMetric.Count("commits")))
        )
            .single()
        row.path("commits").longValue().assert().isEqualTo(6L)
        Unit
    }

    @Test
    fun `an expanded aggregation counts the events, retired ones left out`() = blocking {
        val fixture = given()
        val row = fixture.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(EVENTS)),
                metrics = listOf(AggregationMetric.Count("events"))
            ),
        ).single()
        row.path("events").longValue().assert().isEqualTo(7L)
        Unit
    }

    @Test
    fun `an expanded aggregation sums an event field per event, aliases included`() = blocking {
        val fixture = given()
        val row = fixture.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(EVENTS, name("ItemAdded"))),
                metrics = listOf(
                    AggregationMetric.Count("items"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("body.qty")),
                        "qty"
                    ),
                ),
            ),
        ).single()
        row.path("items").longValue().assert().isEqualTo(3L)
        row.path("qty").doubleValue().assert().isEqualTo(6.0)
        Unit
    }

    @Test
    fun `an expanded aggregation groups events by a field of their commit`() = blocking {
        val fixture = given()
        Assumptions.assumeTrue(fixture.schema.storage.aggregation.rootGroups != SupportMode.NONE)
        val rows = fixture.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(EVENTS)),
                groupBy = listOf(
                    AggregationGroup.Terms(QueryField("aggregateId"), "aggregate", scope = AggregationGroupScope.ROOT)
                ),
                metrics = listOf(AggregationMetric.Count("events")),
            ),
        )
        rows.map { it.path("aggregate").stringValue() to it.path("events").longValue() }
            .assert().containsExactly("o1" to 4L, "o2" to 2L, "o3" to 1L)
        Unit
    }

    @Test
    fun `a root metric over an event field is rejected`() {
        rejected(QueryErrorCodes.ELEMENT_SCOPE_REQUIRED) {
            given().aggregate(
                AggregationQuery(
                    metrics = listOf(
                        AggregationMetric.Numeric(
                            AggregationFunction.SUM,
                            AggregationExpression.Field(QueryField("events.body.qty")),
                            "qty",
                        ),
                    ),
                ),
            )
        }
    }

    // Timeouts (decision D150)

    @Test
    fun `an expired timeout fails a list with the timeout error`() = blocking {
        val fixture = given()
        val error = runCatching {
            fixture.backend.list(
                QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), fixture.schema).withTimeout(Duration.ofNanos(1)),
            ).toList()
        }.exceptionOrNull()
        (error as QueryTimeoutException).errorCode.assert().isEqualTo(QueryErrors.TIMEOUT)
        Unit
    }

    // The gateway reads each record as the model shows it

    @Test
    fun `the gateway reads each event as the aggregate reads it now and masks its body`() = blocking {
        val fixture = given()
        val prefix = "${type.context}.${type.name}."
        val gateway = EventQueryGateways.create(
            type,
            fixture.backend,
            FixedQueryModelSchemaProvider(fixture.schema),
            masks = PayloadMasks(manifest),
            reading = { record ->
                when (record.name.removePrefix(prefix)) {
                    "LegacyNoted" -> null
                    "LineAdded" -> EventRecord(prefix + "ItemAdded", 1, record.payload)
                    "OrderPaid" -> EventRecord(record.name, 2, record.payload.replace("total", "amount"))
                    else -> record
                }
            },
        )
        val records = gateway.list(ListQuery(TenantIdFilter(TENANT), limit = MAX_LIST), QueryCaller.IN_PROCESS).toList()
            .associateBy { it.key() }
        records.getValue(
            "o1@1"
        ).path("events")[0].path("body").path("customerId").stringValue().assert().isEqualTo("c****")
        records.getValue("o2@2").path("events")[0].path("name").stringValue().assert().isEqualTo(prefix + "ItemAdded")
        records.getValue("o1@3").path("events")[0].let { paid ->
            paid.path("revision").intValue().assert().isEqualTo(2)
            paid.path("body").path("amount").intValue().assert().isEqualTo(10)
        }
        records.getValue("o1@4").path("events").isEmpty.assert().isTrue()
        gateway.count(
            CountQuery(events(name("ItemAdded"))),
            QueryCaller.IN_PROCESS
        ).assert().isEqualTo(CountResult(2, true))
        val descriptor = gateway.describe(me.ahoo.wow.query.QueryEntry.HTTP)
        descriptor.model.assert().isEqualTo(QueryModel.EVENTS)
        Unit
    }

    companion object {
        private const val CONTEXT_SUFFIX = 12
        private const val MAX_LIST = 100
        const val TENANT: String = "tenant"
        const val OTHER_TENANT: String = "other-tenant"

        /** 2026-01-05T00:00:00Z. */
        const val T0: Long = 1_767_571_200_000L
        private val EVENTS = QueryField("events")
        private val VERSION = QueryField("version")
        private val COMMIT_TIME = QueryField("commitTime")

        /** A record's `aggregateId@version`. */
        fun ObjectNode.key(): String = "${path("aggregateId").stringValue()}@${path("version").intValue()}"
    }
}
