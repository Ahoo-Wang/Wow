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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.tck.EventQueryBackendSpec.Companion.OTHER_TENANT
import me.ahoo.wow.query.tck.EventQueryBackendSpec.Companion.T0
import me.ahoo.wow.query.tck.EventQueryBackendSpec.Companion.TENANT
import me.ahoo.wow.query.tck.EventQueryBackendSpec.Companion.key
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The fixtures and hooks of the `events` contract ([EventQueryBackendSpec], the class a backend's test extends): the
 * backend's hooks ([store], [binding]), the test's own aggregate [type] and [manifest], the [Fixture] a test queries,
 * and the streams it stores ([stream], [event], [streams], [stampedStreams]).
 *
 * The contract's cases come in families, one class each: [EventAnalyticsCases], then [EventQueryBackendSpec] with the
 * records, the access attributes, the event conditions, sorts, pages, cursors and counts, the timeouts, the gateway
 * and the transport. They form one chain, in that order, each extending the one before, because a class has one
 * superclass and JUnit runs the cases a test class inherits. The order means nothing: every family reads only this
 * class's fixtures, hooks and constants, and the suite's public constants on [EventQueryBackendSpec], never a member
 * of the family it extends, so any order would run the same cases.
 */
abstract class EventQueryFixtures internal constructor() {
    /**
     * Stores [streams], all of [type], where the backend under test reads them, each appended with the transport
     * bytes beside it, null for none: a log stores them apart from the facts, and a Sink is handed
     * them decoded with its record.
     */
    protected abstract suspend fun store(streams: List<Pair<EventStreamRecord, ByteArray?>>)

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
                        state = StateManifest(Manifest.schemaRef("tck.OrderState")),
                        events = listOf(
                            EventManifest("OrderCreated", schema = Manifest.schemaRef("tck.OrderCreated")),
                            EventManifest(
                                "ItemAdded",
                                aliases = listOf("LineAdded"),
                                schema = Manifest.schemaRef("tck.ItemAdded")
                            ),
                            EventManifest("OrderPaid", revision = 2, schema = Manifest.schemaRef("tck.OrderPaid")),
                        ),
                        retired = listOf("LegacyNoted"),
                        access = listOf(
                            AccessAttributeManifest("region", AccessAttributeManifest.Type.STRING),
                            AccessAttributeManifest("teams", AccessAttributeManifest.Type.STRING, multiple = true),
                            AccessAttributeManifest("dept", AccessAttributeManifest.Type.STRING),
                        ),
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
                        "x-wow-sensitive" to mapOf("level" to "display"),
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
    protected class Fixture @InternalWowApi constructor(
        val backend: QueryBackend,
        @property:InternalWowApi val schema: QueryModelSchema,
    ) {
        suspend fun list(query: ListQuery): List<ObjectNode> =
            backend.list(QueryAdmission.Trusted.list(query, schema)).toList()

        suspend fun keys(filter: FilterExpression, sort: List<Sort> = emptyList()): List<String> =
            list(ListQuery(filter, sort = sort, limit = MAX_LIST)).map { it.key() }.sorted()

        suspend fun count(filter: FilterExpression): Long =
            checkNotNull(backend.countBounded(QueryAdmission.Trusted.count(CountQuery(filter), schema)).count)

        suspend fun aggregate(query: AggregationQuery): List<ObjectNode> =
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
    }

    protected suspend fun given(
        streams: List<EventStreamRecord> = streams(),
        transported: (Int) -> Boolean = { false },
    ): Fixture {
        store(streams.mapIndexed { index, stream -> stream to TRANSPORT.takeIf { transported(index) } })
        val binding = binding(type)
        val provider = QueryModelCompiler.of(events = schemas).compile(context, binding.storage)
        return Fixture(binding.backend, provider.schema())
    }

    /** One stream of this test's [type]. */
    protected fun stream(
        id: String,
        version: Int,
        commitTime: Long,
        events: List<EventRecord>,
        tenantId: String = TENANT,
        scope: Scope = Scope("owner-1"),
        operator: Operator? = null,
        access: Map<String, Any?>? = null,
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
        access = access,
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

    internal fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    internal fun name(local: String) = EqualFilter(QueryField("name"), "${type.context}.${type.name}.$local")

    internal fun events(predicate: FilterExpression) = ElementMatchFilter(EVENTS, predicate)

    internal fun rejected(code: String, block: suspend () -> Any?) = blocking {
        val error = runCatching { block() }.exceptionOrNull()
        (error is QuerySchemaValidationException).assert().describedAs("$error").isTrue()
        (error as QuerySchemaValidationException).violation?.code.assert().isEqualTo(code)
    }

    /**
     * `o1`'s single attribute `region` went from `east` to `west`, then to none, and its multiple `teams` from `[a, b]`
     * to `[b]`, then to `[]`, each stream as the log holds it; `o2` was appended before the log recorded them; `o3`
     * declares another attribute only. Every stamped stream holds the built-in `owner` and `space` too.
     */
    /**
     * Streams of the aggregates' current attributes, as the stamping keeps every stream of an aggregate: an aggregate's
     * streams share them, other aggregates hold others. `o6` is stamped with nothing: no owner, no space, its declared
     * attributes none or empty, so its `access` holds no value a store indexes, and it is stamped all the same.
     */
    internal fun stampedStreams(): List<EventStreamRecord> {
        val east = access("region" to "east", "teams" to listOf("a", "b"))
        return listOf(
            stream("o1", 1, T0, listOf(event("OrderCreated", "{}")), access = east),
            stream("o1", 2, T0 + 1, listOf(event("ItemAdded", "{}")), access = east),
            stream("o2", 1, T0, listOf(event("OrderCreated", "{}")), access = null),
            stream("o3", 1, T0, listOf(event("OrderCreated", "{}")), access = access("dept" to "eng")),
            stream(
                "o4",
                1,
                T0 + 1,
                listOf(event("OrderCreated", "{}")),
                access = access("region" to "west", "teams" to listOf("b"))
            ),
            stream(
                "o5",
                1,
                T0 + 2,
                listOf(event("OrderCreated", "{}")),
                access = access("region" to null, "teams" to emptyList<String>())
            ),
            stream(
                "o6",
                1,
                T0 + 2,
                listOf(event("OrderCreated", "{}")),
                scope = Scope.NONE,
                access = linkedMapOf(
                    "owner" to null,
                    "space" to null,
                    "region" to null,
                    "teams" to emptyList<String>(),
                ),
            ),
        )
    }

    /** The attributes of a stream of `owner-1` with [declared]. */
    internal fun access(vararg declared: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("owner" to "owner-1", "space" to null) + declared

    internal companion object {
        const val CONTEXT_SUFFIX = 12
        const val MAX_LIST = 100
        val EVENTS = QueryField("events")

        /** A transport as the runtime appends it: a wait past COMMITTED, keys reserved and released, a trace. */
        val TRANSPORT: ByteArray = TransportCodec.encode(
            Transport.of(
                wait = WaitContext.of(
                    "w-1",
                    Origin.of("node-1", "10.0.0.7:8081", "order-service"),
                    listOf(Hop.handled("projector", "tck"), Hop.snapshot()),
                    1_767_571_210_000L,
                ),
                reservations = listOf(ReservationKey.of("email", "a@b.c"), ReservationKey.of("login", "x", true)),
                trace = mapOf("traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),
                releases = listOf(ReservationKey.of("email", "old@b.c")),
            ),
        )
    }
}
