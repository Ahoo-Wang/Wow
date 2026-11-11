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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsNotNullFilter
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
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.events.EventQueryGateways
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/**
 * The contract of an `events` query backend: every events backend passes it. A backend's test extends
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
 *
 * The cases come in families, each a parent class of this one ([EventQueryFixtures] lists them); this class
 * holds every family but the per-event analytics ([EventAnalyticsCases]).
 */
abstract class EventQueryBackendSpec : EventAnalyticsCases() {
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
        record.path("operator").path("actedElsewhere").booleanValue().assert().isFalse()
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

    // The access attributes each stream carries

    @Test
    fun `a record shows the access attributes its stream carries, null when it carries none`() = blocking {
        val records = given(stampedStreams()).list(ListQuery(MatchAllFilter, limit = MAX_LIST)).associateBy { it.key() }
        fun access(key: String) = records.getValue(key).path("access")
        access("o1@1").path("region").stringValue().assert().isEqualTo("east")
        access("o1@1").path("teams").toList().map { it.stringValue() }.assert().containsExactly("a", "b")
        access("o1@1").path("owner").stringValue().assert().isEqualTo("owner-1")
        access("o5@1").path("region").isNull.assert().isTrue()
        access("o5@1").path("teams").let { (it.isArray && it.isEmpty).assert().describedAs("$it").isTrue() }
        access("o2@1").isNull.assert().isTrue()
        access("o6@1").isObject.assert().isTrue()
        Unit
    }

    @Test
    fun `access attributes select streams, a multiple one by any of its values, an unstamped stream by none`() =
        blocking {
            val fixture = given(stampedStreams())
            fixture.keys(EqualFilter(QueryField("access.region"), "east")).assert().containsExactly("o1@1", "o1@2")
            fixture.keys(InFilter(QueryField("access.region"), listOf("east", "west"))).assert()
                .containsExactly("o1@1", "o1@2", "o4@1")
            fixture.keys(EqualFilter(QueryField("access.teams"), "b")).assert().containsExactly("o1@1", "o1@2", "o4@1")
            fixture.keys(EqualFilter(QueryField("access.owner"), "owner-1")).assert()
                .containsExactly("o1@1", "o1@2", "o3@1", "o4@1", "o5@1")
            fixture.keys(IsNotNullFilter(QueryField("access"))).assert()
                .containsExactly("o1@1", "o1@2", "o3@1", "o4@1", "o5@1", "o6@1")
            fixture.count(EqualFilter(QueryField("access.teams"), "b")).assert().isEqualTo(3L)
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
        assumeSupported(fixture.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
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

    // Timeouts

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
        val descriptor = gateway.describe(QueryEntry.HTTP)
        descriptor.model.assert().isEqualTo(QueryModel.EVENTS)
        Unit
    }

    // The transport stored beside a stream

    /**
     * Every other stream is appended carrying a transport (a wait with its origin and remaining hops, reservations,
     * releases and a trace): the events query reads facts only, so its records, counts, pages, cursor, aggregations
     * and expanded aggregations are what they are without it, and no record shows a field of it.
     */
    @Test
    fun `streams appended with a transport read as the facts alone`() = blocking {
        val fixture = given(transported = { it % 2 == 0 })
        val records = fixture.list(ListQuery(MatchAllFilter, limit = MAX_LIST))
        records.map { it.key() }.sorted().assert()
            .containsExactly("o1@1", "o1@2", "o1@3", "o1@4", "o2@1", "o2@2", "o3@1")
        records.forEach { record ->
            record.propertyNames().filter { it in TRANSPORT_FIELDS }.assert().describedAs(record.key()).isEmpty()
        }
        val first = records.single { it.key() == "o1@1" }
        first.path("commandId").stringValue().assert().isEqualTo("o1-command-1")
        first.path("events").single().path("body").path("customerId").stringValue().assert().isEqualTo("c-one")
        fixture.count(MatchAllFilter).assert().isEqualTo(7L)
        fixture.keys(AggregateIdFilter("o2")).assert().containsExactly("o2@1", "o2@2")
        val paged = PagedQuery(
            TenantIdFilter(TENANT),
            sort = listOf(Sort(COMMIT_TIME, Sort.Direction.ASC)),
            pagination = Pagination(2, 2),
            total = TotalMode.EXACT,
        )
        val page = fixture.backend.paged(QueryAdmission.Trusted.paged(paged, fixture.schema))
        page.list.map { it.key() }.assert().containsExactly("o1@2", "o1@3")
        page.total.assert().isEqualTo(6L)
        if (fixture.schema.storage.paging.keyset != SupportMode.NONE) {
            val seen = mutableListOf<String>()
            var cursor: String? = null
            do {
                val admitted = QueryAdmission.Trusted.cursor(
                    CursorQuery(MatchAllFilter, size = 2, cursor = cursor),
                    fixture.schema,
                )
                val next = fixture.backend.cursor(admitted)
                seen += next.list.map { it.key() }
                cursor = next.nextCursor
            } while (cursor != null)
            seen.assert().containsExactly("o3@1", "o1@1", "o1@2", "o1@3", "o1@4", "o2@1", "o2@2")
        }
        fixture.aggregate(AggregationQuery(TenantIdFilter(TENANT), metrics = listOf(AggregationMetric.Count("c"))))
            .single().path("c").longValue().assert().isEqualTo(6L)
        fixture.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(EVENTS, name("ItemAdded"))),
                metrics = listOf(
                    AggregationMetric.Count("items"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("body.qty")),
                        "qty",
                    ),
                ),
            ),
        ).single().let { row ->
            row.path("items").longValue().assert().isEqualTo(3L)
            row.path("qty").doubleValue().assert().isEqualTo(6.0)
        }
        Unit
    }

    companion object {
        const val TENANT: String = "tenant"
        const val OTHER_TENANT: String = "other-tenant"

        /** 2026-01-05T00:00:00Z. */
        const val T0: Long = 1_767_571_200_000L
        private val VERSION = QueryField("version")
        private val COMMIT_TIME = QueryField("commitTime")

        /** The fields of a transport, none of which a record shows. */
        private val TRANSPORT_FIELDS = setOf("transport", "wait", "reservations", "releases", "trace")

        /** A record's `aggregateId@version`. */
        fun ObjectNode.key(): String = "${path("aggregateId").stringValue()}@${path("version").intValue()}"
    }
}
