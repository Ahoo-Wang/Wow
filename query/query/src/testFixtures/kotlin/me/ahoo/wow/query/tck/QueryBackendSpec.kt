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
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.query.QueryRequestException
import me.ahoo.wow.query.QueryTimeoutException
import me.ahoo.wow.query.aggregate
import me.ahoo.wow.query.countBounded
import me.ahoo.wow.query.cursor
import me.ahoo.wow.query.list
import me.ahoo.wow.query.paged
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.querySchema
import me.ahoo.wow.query.single
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The query backend contract (decision D126; the 9.x `SnapshotQueryBackendSpec` of wow-tck, ported to the V10 snapshot
 * model): every snapshot query backend passes it. A backend's test extends it, stores the snapshots each test gives in
 * [store], and supplies its binding of an aggregate type in [binding].
 *
 * Each test runs on its own aggregate type (a fresh context), so a backend that keeps a table or a collection per
 * context isolates the tests. The schema is compiled as the Catalog compiles it: the snapshot system fields, the
 * order state [DECLARATION] and the backend's storage facts. Queries are admitted through [QueryAdmission.Trusted]
 * (no caller scope, no default deletion scope) and run through the core's query shapes (`BackendQueries.kt`), except
 * the gateway tests, which run the whole admission.
 *
 * Features a storage may decline (keyset pages, unbounded streams, distinct counts, percentiles, FIRST / LAST, root
 * groups, temporal aggregation) are assumed only where its facts grant them; a declined feature skips its test.
 */
@Suppress("LargeClass")
abstract class QueryBackendSpec {
    /** Stores [snapshots], all of [type], where the backend under test reads them. */
    protected abstract suspend fun store(snapshots: List<SnapshotRecord>)

    /** The query backend of [type]'s snapshots, with its storage adapter. */
    protected abstract fun binding(type: AggregateType): QueryBackendBinding<QueryBackend>

    /** The ids of the [FilterSemantics] cases this backend is known to diverge on; they are skipped. */
    protected open val semanticDivergences: Set<String> = emptySet()

    /** The aggregate type of this test: a fresh context, so each test has its own records. */
    protected val type: AggregateType =
        AggregateType("tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX), "order")

    private val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)

    /** The backend under test and the schema compiled over its facts, once [snapshots] are stored. */
    protected class Fixture(val backend: QueryBackend, val schema: QueryModelSchema) {
        suspend fun list(query: ListQuery): List<ObjectNode> =
            backend.list(QueryAdmission.Trusted.list(query, schema)).toList()

        suspend fun ids(filter: FilterExpression, sort: List<Sort> = emptyList()): List<String> =
            list(ListQuery(filter, sort = sort, limit = MAX_LIST)).map { it.id() }

        suspend fun aggregate(query: AggregationQuery): List<ObjectNode> =
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
    }

    protected suspend fun given(snapshots: List<SnapshotRecord>): Fixture {
        store(snapshots)
        val binding = binding(type)
        val provider = DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, DECLARATION)))),
            binding.storage,
        )
        return Fixture(binding.backend, provider.schema())
    }

    /** A snapshot of this test's [type] whose state is [state], a JSON object. */
    @Suppress("LongParameterList")
    protected fun snapshot(
        id: String,
        state: String,
        tenantId: String = TENANT,
        scope: Scope = Scope("owner-1"),
        deleted: Boolean = false,
        version: Int = 1,
        lastEventTime: Long = T0,
        tags: Map<String, List<String>> = emptyMap(),
    ) = SnapshotRecord(
        ref = type.ref(tenantId, id),
        version = version,
        schemaFingerprint = "tck",
        state = state,
        scope = scope,
        deleted = deleted,
        firstEventTime = T0 - DAY,
        lastEventTime = lastEventTime,
        tags = tags,
    )

    /** Five orders over two tenants, one deleted, one tagged: the records most tests read. */
    protected fun orders(): List<SnapshotRecord> = listOf(
        snapshot(
            "o1",
            """{"name":"Alpha","status":"PAID","amount":10.5,"quantity":1,"createdAt":$T0,"keywords":["a","b"],""" +
                """"lines":[{"sku":"A","price":5.0,"quantity":1},{"sku":"B","price":5.5,"quantity":2}]}""",
        ),
        snapshot(
            "o2",
            """{"name":"Beta","status":"PAID","amount":20,"quantity":2,"createdAt":${T0 + DAY},"keywords":["b"],""" +
                """"lines":[{"sku":"A","price":20,"quantity":1}]}""",
            scope = Scope("owner-2"),
            version = 2,
            lastEventTime = T0 + DAY,
        ),
        snapshot(
            "o3",
            """{"name":"Gamma","status":"NEW","amount":30,"quantity":2,"createdAt":${T0 + 2 * DAY},"keywords":[],""" +
                """"lines":[]}""",
            tenantId = OTHER_TENANT,
            scope = Scope("owner-1", "space-1"),
            version = 3,
            lastEventTime = T0 + 2 * DAY,
        ),
        snapshot(
            "o4",
            """{"name":"Delta","status":"NEW","amount":40,"quantity":3,"createdAt":${T0 + 3 * DAY},""" +
                """"lines":[{"sku":"C","price":40,"quantity":4}]}""",
            deleted = true,
            version = 4,
            lastEventTime = T0 + 3 * DAY,
        ),
        snapshot(
            "o5",
            """{"name":"Epsilon","status":"CANCELLED","amount":50,"quantity":3,"createdAt":${T0 + 4 * DAY},""" +
                """"lines":[{"sku":"B","price":10,"quantity":5}]}""",
            scope = Scope.NONE,
            version = 5,
            lastEventTime = T0 + 4 * DAY,
            tags = mapOf("region" to listOf("east")),
        ),
    )

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    // Filters

    @TestFactory
    fun `filter semantics matrix`(): List<DynamicTest> {
        val probes = SemanticProbe.ALL.values.flatten()
        val fixture = blocking {
            given(
                probes.map { probe ->
                    val property = PROBE_FIELDS.getValue(probe.shape).removePrefix("state.")
                    val state = probe.json?.let { """{"$property":$it}""" } ?: "{}"
                    snapshot(probeId(probe), state)
                },
            )
        }
        return FilterSemantics.CASES.map { case ->
            DynamicTest.dynamicTest(case.id) {
                Assumptions.assumeFalse(case.id in semanticDivergences) {
                    "Known divergence from the canonical semantics: ${case.id}."
                }
                val shapeProbes = checkNotNull(SemanticProbe.ALL[case.shape])
                val filter = AndFilter(
                    listOf(
                        AggregateIdsFilter(shapeProbes.map(::probeId)),
                        case.filter(QueryField(PROBE_FIELDS.getValue(case.shape))),
                    ),
                )
                val byId = shapeProbes.associateBy(::probeId)
                blocking { fixture.ids(filter) }.map { checkNotNull(byId[it]) { "Unexpected record [$it]." }.name }
                    .toSet().assert().describedAs(case.id).isEqualTo(case.matches)
            }
        }
    }

    @Test
    fun `filter semantics cover every field operator`() {
        val covered = FilterSemantics.CASES.map { it.operator }.toSet() + FilterSemantics.UNCOVERED.keys
        val fieldOperators = FilterOperator.entries.filter {
            me.ahoo.wow.query.spec.FilterOperatorSpec.of(it).target == me.ahoo.wow.query.spec.OperatorTarget.FIELD
        }
        covered.containsAll(fieldOperators).assert().isTrue()
    }

    @Test
    fun `system field filters select by id, tenant, owner, space and deletion`() = blocking {
        val fixture = given(orders())
        fixture.ids(AggregateIdFilter("o2")).assert().containsExactly("o2")
        fixture.ids(IdsFilter(listOf("o1", "o3"))).assert().containsExactlyInAnyOrder("o1", "o3")
        fixture.ids(TenantIdFilter(OTHER_TENANT)).assert().containsExactly("o3")
        fixture.ids(OwnerIdFilter("owner-1")).assert().containsExactlyInAnyOrder("o1", "o3", "o4")
        fixture.ids(SpaceIdFilter("space-1")).assert().containsExactly("o3")
        fixture.ids(DeletionFilter(DeletionState.ACTIVE)).assert().containsExactlyInAnyOrder("o1", "o2", "o3", "o5")
        fixture.ids(DeletionFilter(DeletionState.DELETED)).assert().containsExactly("o4")
        fixture.ids(DeletionFilter(DeletionState.ALL)).assert().hasSize(5)
        fixture.ids(EqualFilter(QueryField("tags.region"), "east")).assert().containsExactly("o5")
        fixture.ids(EqualFilter(QueryField("version"), 2)).assert().containsExactly("o2")
        fixture.ids(GreaterThanFilter(QueryField("lastEventTime"), T0 + 2 * DAY))
            .assert().containsExactlyInAnyOrder("o4", "o5")
        Unit
    }

    @Test
    fun `logical filters combine AND, OR and NOR`() = blocking {
        val fixture = given(orders())
        val paid = EqualFilter(QueryField("state.status"), "PAID")
        val large = GreaterThanOrEqualFilter(QueryField("state.amount"), 20)
        fixture.ids(AndFilter(listOf(paid, large))).assert().containsExactly("o2")
        fixture.ids(OrFilter(listOf(paid, EqualFilter(QueryField("state.status"), "CANCELLED"))))
            .assert().containsExactlyInAnyOrder("o1", "o2", "o5")
        fixture.ids(NorFilter(listOf(paid, large))).assert().isEmpty()
        fixture.ids(NorFilter(listOf(paid))).assert().containsExactlyInAnyOrder("o3", "o4", "o5")
        Unit
    }

    @Test
    fun `element match selects records with one element matching every predicate`() = blocking {
        val fixture = given(orders())
        val sameLine = ElementMatchFilter(
            QueryField("state.lines"),
            AndFilter(
                listOf(
                    EqualFilter(QueryField("sku"), "B"),
                    GreaterThanOrEqualFilter(QueryField("quantity"), 2),
                ),
            ),
        )
        fixture.ids(sameLine).assert().containsExactlyInAnyOrder("o1", "o5")
        // An element field is reached only through its element scope.
        val outside = runCatching { fixture.ids(EqualFilter(QueryField("state.lines.sku"), "A")) }.exceptionOrNull()
        (outside as QuerySchemaValidationException).violation.assert()
            .isEqualTo(QueryViolation.ElementScopeRequired(QueryField("state.lines.sku")))
        Unit
    }

    @Test
    fun `relative-now filters resolve against the server clock in the field's encoding`() = blocking {
        val now = System.currentTimeMillis()
        val fixture = given(
            listOf(
                snapshot("past", """{"createdAt":${now - DAY}}"""),
                snapshot("future", """{"createdAt":${now + DAY}}"""),
            ),
        )
        fixture.ids(BeforeNowFilter(QueryField("state.createdAt"))).assert().containsExactly("past")
        fixture.ids(AfterNowFilter(QueryField("state.createdAt"))).assert().containsExactly("future")
        Unit
    }

    @Test
    fun `EXPRESSION filter compares the computed value and skips records without one`() = blocking {
        val fixture = given(orders() + snapshot("empty", "{}"))
        val total = AggregationExpression.Binary(
            me.ahoo.wow.api.query.AggregationExpressionOperator.MULTIPLY,
            AggregationExpression.Field(QueryField("state.amount")),
            AggregationExpression.Field(QueryField("state.quantity")),
        )
        fixture.ids(ExpressionFilter(total, ComparisonOperator.GTE, 120.0))
            .assert().containsExactlyInAnyOrder("o4", "o5")
        Unit
    }

    // Sort, list, single and projection

    @Test
    fun `list sorts by several fields in either direction and honors the limit`() = blocking {
        val fixture = given(orders())
        val sort = listOf(Sort(QueryField("state.status"), Sort.Direction.ASC), Sort(AMOUNT, Sort.Direction.DESC))
        fixture.list(ListQuery(MatchAllFilter, sort = sort, limit = 3)).map { it.id() }
            .assert().containsExactly("o5", "o4", "o3")
        fixture.ids(MatchAllFilter, listOf(Sort(AMOUNT, Sort.Direction.DESC)))
            .assert().containsExactly("o5", "o4", "o3", "o2", "o1")
        Unit
    }

    @Test
    fun `list without a limit streams every record`() = blocking {
        val fixture = given(orders())
        Assumptions.assumeTrue(fixture.schema.storage.paging.unboundedStream != SupportMode.NONE)
        fixture.list(ListQuery(MatchAllFilter)).assert().hasSize(5)
        Unit
    }

    @Test
    fun `single returns the first record in sort order, or none`() = blocking {
        val fixture = given(orders())
        val first = fixture.backend.single(
            QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter, sort = listOf(Sort(AMOUNT, Sort.Direction.DESC))), fixture.schema),
        )
        first?.id().assert().isEqualTo("o5")
        fixture.backend.single(QueryAdmission.Trusted.single(SingleQuery(AggregateIdFilter("none")), fixture.schema))
            .assert().isNull()
        Unit
    }

    @Test
    fun `single exposes the snapshot record shape`() = blocking {
        val fixture = given(orders())
        val record = checkNotNull(
            fixture.backend.single(QueryAdmission.Trusted.single(SingleQuery(AggregateIdFilter("o3")), fixture.schema)),
        )
        record.path("tenantId").stringValue().assert().isEqualTo(OTHER_TENANT)
        record.path("aggregateId").stringValue().assert().isEqualTo("o3")
        record.path("version").intValue().assert().isEqualTo(3)
        record.path("ownerId").stringValue().assert().isEqualTo("owner-1")
        record.path("spaceId").stringValue().assert().isEqualTo("space-1")
        record.path("deleted").booleanValue().assert().isFalse()
        record.path("firstEventTime").longValue().assert().isEqualTo(T0 - DAY)
        record.path("lastEventTime").longValue().assert().isEqualTo(T0 + 2 * DAY)
        record.path("state").path("name").stringValue().assert().isEqualTo("Gamma")
        Unit
    }

    @Test
    fun `projection preserves scalar and object subtree semantics`() = blocking {
        val fixture = given(orders())
        suspend fun query(projection: Projection): ObjectNode =
            fixture.list(ListQuery(AggregateIdFilter("o1"), projection = projection, limit = 1)).single()

        query(Projection(include = listOf(QueryField("state")))).path("state").let { state ->
            state.has("name").assert().isTrue()
            state.has("lines").assert().isTrue()
        }
        query(Projection(include = listOf(QueryField("state.name")))).path("state").let { state ->
            state.has("name").assert().isTrue()
            state.has("lines").assert().isFalse()
        }
        query(Projection(exclude = listOf(QueryField("state")))).has("state").assert().isFalse()
        query(Projection(exclude = listOf(QueryField("state.name")))).path("state").let { state ->
            state.has("name").assert().isFalse()
            state.has("lines").assert().isTrue()
        }
        Unit
    }

    // Pages and counts

    @Test
    fun `paged returns its window with the exact total`() = blocking {
        val fixture = given(orders())
        val query = PagedQuery(
            MatchAllFilter,
            sort = listOf(Sort(AMOUNT, Sort.Direction.ASC)),
            pagination = Pagination(2, 2),
            total = TotalMode.EXACT,
        )
        val page = fixture.backend.paged(QueryAdmission.Trusted.paged(query, fixture.schema))
        page.list.map { it.id() }.assert().containsExactly("o3", "o4")
        page.total.assert().isEqualTo(5L)
        page.exact.assert().isTrue()
        Unit
    }

    @Test
    fun `paged bounded total stops at its limit and says it is not exact`() = blocking {
        val fixture = given(orders())
        val query = PagedQuery(MatchAllFilter, sort = listOf(Sort(AMOUNT, Sort.Direction.ASC)), pagination = Pagination(1, 2))
        val admitted = QueryAdmission.Trusted.paged(query, fixture.schema)
        fixture.backend.paged(admitted, totalLimit = 3).let { page ->
            page.list.map { it.id() }.assert().containsExactly("o1", "o2")
            page.total.assert().isEqualTo(3L)
            page.exact.assert().isFalse()
        }
        fixture.backend.paged(admitted, totalLimit = 5).let { page ->
            page.total.assert().isEqualTo(5L)
            page.exact.assert().isTrue()
        }
        fixture.backend.paged(admitted, totalLimit = 10).let { page ->
            page.total.assert().isEqualTo(5L)
            page.exact.assert().isTrue()
        }
        Unit
    }

    @Test
    fun `paged without a total counts nothing`() = blocking {
        val fixture = given(orders())
        val query = PagedQuery(MatchAllFilter, pagination = Pagination(1, 10), total = TotalMode.NONE)
        val page = fixture.backend.paged(QueryAdmission.Trusted.paged(query, fixture.schema))
        page.list.assert().hasSize(5)
        page.total.assert().isNull()
        page.exact.assert().isFalse()
        Unit
    }

    @Test
    fun `count is exact below its limit and bounded above it`() = blocking {
        val fixture = given(orders())
        suspend fun count(filter: FilterExpression, limit: Int?): CountResult =
            fixture.backend.countBounded(QueryAdmission.Trusted.count(CountQuery(filter, limit), fixture.schema))

        count(MatchAllFilter, 3).assert().isEqualTo(CountResult(3, exact = false))
        count(MatchAllFilter, 5).assert().isEqualTo(CountResult(5, exact = true))
        count(MatchAllFilter, 10).assert().isEqualTo(CountResult(5, exact = true))
        count(MatchAllFilter, null).assert().isEqualTo(CountResult(5, exact = true))
        count(EqualFilter(QueryField("state.status"), "PAID"), 1).assert().isEqualTo(CountResult(1, exact = false))
        count(EqualFilter(QueryField("state.status"), "NONE"), 1).assert().isEqualTo(CountResult(0, exact = true))
        Unit
    }

    // Cursors

    @Test
    fun `cursor traverses tied records once each in sort order`() = blocking {
        val fixture = given(orders())
        Assumptions.assumeTrue(fixture.schema.storage.paging.keyset != SupportMode.NONE)
        val sort = listOf(Sort(QueryField("state.status"), Sort.Direction.DESC))
        val pages = mutableListOf<List<String>>()
        var cursor: String? = null
        do {
            val query = CursorQuery(MatchAllFilter, sort = sort, size = 2, cursor = cursor)
            val page = fixture.backend.cursor(QueryAdmission.Trusted.cursor(query, fixture.schema))
            pages += page.list.map { it.id() }
            cursor = page.nextCursor
        } while (cursor != null && pages.size < MAX_LIST)
        pages.assert().containsExactly(listOf("o1", "o2"), listOf("o3", "o4"), listOf("o5"))
        Unit
    }

    @Test
    fun `cursor supports descending multi field sort`() = blocking {
        val fixture = given(orders())
        Assumptions.assumeTrue(fixture.schema.storage.paging.keyset != SupportMode.NONE)
        val sort = listOf(Sort(QueryField("state.quantity"), Sort.Direction.DESC), Sort(AMOUNT, Sort.Direction.DESC))
        val first = fixture.backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = sort, size = 3), fixture.schema))
        first.list.map { it.id() }.assert().containsExactly("o5", "o4", "o3")
        val second = fixture.backend.cursor(
            QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = sort, size = 3, cursor = first.nextCursor), fixture.schema),
        )
        second.list.map { it.id() }.assert().containsExactly("o2", "o1")
        second.nextCursor.assert().isNull()
        Unit
    }

    @Test
    fun `cursor breaks ties by tenant before aggregate id`() = blocking {
        // The same aggregate id in two tenants: a cross-tenant cursor (trusted, unscoped) must return both.
        val shared = """{"name":"Shared","status":"PAID","amount":1,"quantity":1,"createdAt":$T0}"""
        val fixture = given(
            listOf(
                snapshot("dup", shared),
                snapshot("dup", shared, tenantId = OTHER_TENANT),
                snapshot("solo", shared),
            ),
        )
        Assumptions.assumeTrue(fixture.schema.storage.paging.keyset != SupportMode.NONE)
        val sort = listOf(Sort(QueryField("state.status"), Sort.Direction.ASC))
        val seen = mutableListOf<Pair<String, String>>()
        var cursor: String? = null
        do {
            val query = CursorQuery(MatchAllFilter, sort = sort, size = 1, cursor = cursor)
            val admitted = QueryAdmission.Trusted.cursor(query, fixture.schema)
            admitted.query.sort.map { it.field.path }.assert().containsExactly("state.status", "tenantId", "aggregateId")
            val page = fixture.backend.cursor(admitted)
            seen += page.list.map { it.path("tenantId").stringValue() to it.id() }
            cursor = page.nextCursor
        } while (cursor != null && seen.size < MAX_LIST)
        seen.assert().containsExactly(OTHER_TENANT to "dup", TENANT to "dup", TENANT to "solo")
        Unit
    }

    @Test
    fun `cursor returns an empty terminal page`() = blocking {
        val fixture = given(orders())
        Assumptions.assumeTrue(fixture.schema.storage.paging.keyset != SupportMode.NONE)
        val page = fixture.backend.cursor(
            QueryAdmission.Trusted.cursor(CursorQuery(AggregateIdFilter("none"), size = 2), fixture.schema),
        )
        page.list.assert().isEmpty()
        page.nextCursor.assert().isNull()
        Unit
    }

    @Test
    fun `cursor token issued for another sort is the invalid cursor`() = blocking {
        val fixture = given(orders())
        Assumptions.assumeTrue(fixture.schema.storage.paging.keyset != SupportMode.NONE)
        val byAmount = CursorQuery(MatchAllFilter, sort = listOf(Sort(AMOUNT, Sort.Direction.ASC)), size = 1)
        val token = checkNotNull(fixture.backend.cursor(QueryAdmission.Trusted.cursor(byAmount, fixture.schema)).nextCursor)
        val byName = CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("state.name"), Sort.Direction.ASC)), size = 1, cursor = token)
        val error = runCatching { fixture.backend.cursor(QueryAdmission.Trusted.cursor(byName, fixture.schema)) }
            .exceptionOrNull()
        (error as QueryRequestException).violation.assert().isEqualTo(QueryViolation.InvalidCursor)
        Unit
    }

    // Aggregation

    @Test
    fun `aggregation summarizes every record with count, sum, avg, min and max`() = blocking {
        val fixture = given(orders())
        val row = fixture.aggregate(
            AggregationQuery(
                filter = DeletionFilter(DeletionState.ACTIVE),
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    numeric(AggregationFunction.SUM, "total"),
                    numeric(AggregationFunction.AVG, "average"),
                    numeric(AggregationFunction.MIN, "minimum"),
                    numeric(AggregationFunction.MAX, "maximum"),
                ),
            ),
        ).single()
        row.path("count").longValue().assert().isEqualTo(4L)
        row.path("total").doubleValue().assert().isEqualTo(110.5)
        row.path("average").doubleValue().assert().isEqualTo(27.625)
        row.path("minimum").doubleValue().assert().isEqualTo(10.5)
        row.path("maximum").doubleValue().assert().isEqualTo(50.0)
        Unit
    }

    @Test
    fun `aggregation returns one empty summary row`() = blocking {
        val fixture = given(orders())
        val row = fixture.aggregate(
            AggregationQuery(
                filter = AggregateIdFilter("none"),
                metrics = listOf(AggregationMetric.Count("count"), numeric(AggregationFunction.SUM, "total")),
            ),
        ).single()
        row.path("count").longValue().assert().isEqualTo(0L)
        row.path("total").isNull.assert().isTrue()
        Unit
    }

    @Test
    fun `aggregation groups by terms in group order`() = blocking {
        val fixture = given(orders())
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(AggregationMetric.Count("count"), numeric(AggregationFunction.SUM, "total")),
            ),
        )
        rows.map { it.path("status").stringValue() }.assert().containsExactly("CANCELLED", "NEW", "PAID")
        rows.map { it.path("count").longValue() }.assert().containsExactly(1L, 2L, 2L)
        rows.map { it.path("total").doubleValue() }.assert().containsExactly(50.0, 70.0, 30.5)
        Unit
    }

    @Test
    fun `aggregation sorts groups by a metric and takes the top N`() = blocking {
        val fixture = given(orders())
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(numeric(AggregationFunction.SUM, "total")),
                sort = listOf(Sort(QueryField("total"), Sort.Direction.DESC)),
                limit = 2,
            ),
        )
        rows.map { it.path("status").stringValue() }.assert().containsExactly("NEW", "CANCELLED")
        Unit
    }

    @Test
    fun `aggregation HAVING keeps the matching groups`() = blocking {
        val fixture = given(orders())
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(AggregationMetric.Count("count")),
                having = HavingExpression.Condition("count", ComparisonOperator.GTE, 2.0),
            ),
        )
        rows.map { it.path("status").stringValue() }.assert().containsExactly("NEW", "PAID")
        Unit
    }

    @Test
    fun `aggregation HAVING that matches no group returns no row`() = blocking {
        val fixture = given(orders())
        Assumptions.assumeTrue(fixture.schema.storage.aggregation.having != SupportMode.NONE)
        val byStatus = AggregationQuery(
            filter = MatchAllFilter,
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
            metrics = listOf(AggregationMetric.Count("count")),
            having = HavingExpression.Condition("count", ComparisonOperator.GTE, 10.0),
        )
        // Groups exist, but HAVING removes all of them: no row, and no empty summary in their place.
        fixture.aggregate(byStatus).assert().isEmpty()
        // No record matches: no group, so no row either.
        fixture.aggregate(byStatus.copy(filter = AggregateIdFilter("none"))).assert().isEmpty()
        Unit
    }

    @Test
    fun `aggregation metric filters count a subset of each group`() = blocking {
        val fixture = given(orders())
        val row = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                metrics = listOf(
                    AggregationMetric.Count("all"),
                    AggregationMetric.Count("paid", EqualFilter(QueryField("state.status"), "PAID")),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(AMOUNT),
                        "paidTotal",
                        InFilter(QueryField("state.status"), listOf("PAID")),
                    ),
                ),
            ),
        ).single()
        row.path("all").longValue().assert().isEqualTo(5L)
        row.path("paid").longValue().assert().isEqualTo(2L)
        row.path("paidTotal").doubleValue().assert().isEqualTo(30.5)
        Unit
    }

    @Test
    fun `aggregation expands elements and sums each element group`() = blocking {
        val fixture = given(orders())
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                elements = listOf(AggregationElement(QueryField("state.lines"))),
                groupBy = listOf(AggregationGroup.Terms(QueryField("sku"), "sku")),
                metrics = listOf(
                    AggregationMetric.Count("lines"),
                    AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(QueryField("quantity")), "quantity"),
                ),
            ),
        )
        rows.map { it.path("sku").stringValue() }.assert().containsExactly("A", "B", "C")
        rows.map { it.path("lines").longValue() }.assert().containsExactly(2L, 2L, 1L)
        rows.map { it.path("quantity").doubleValue() }.assert().containsExactly(2.0, 7.0, 4.0)
        Unit
    }

    @Test
    fun `aggregation groups the elements by a field of their root record`() = blocking {
        val fixture = given(orders())
        Assumptions.assumeTrue(fixture.schema.storage.aggregation.rootGroups != SupportMode.NONE)
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                elements = listOf(AggregationElement(QueryField("state.lines"))),
                groupBy = listOf(
                    AggregationGroup.Terms(QueryField("state.status"), "status", scope = AggregationGroupScope.ROOT),
                ),
                metrics = listOf(AggregationMetric.Count("lines")),
            ),
        )
        rows.map { it.path("status").stringValue() to it.path("lines").longValue() }
            .assert().containsExactly("CANCELLED" to 1L, "NEW" to 1L, "PAID" to 3L)
        Unit
    }

    @Test
    fun `aggregation counts distinct values`() = blocking {
        val fixture = given(orders())
        Assumptions.assumeTrue(fixture.schema.storage.aggregation.distinctCount != SupportMode.NONE)
        val row = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                metrics = listOf(AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField("state.status")), "statuses")),
            ),
        ).single()
        row.path("statuses").longValue().assert().isEqualTo(3L)
        Unit
    }

    @Test
    fun `aggregation groups an epoch field by day`() = blocking {
        val fixture = given(orders())
        val createdAt = fixture.schema.field(QueryField("state.createdAt"))
        Assumptions.assumeTrue(createdAt?.binding(QueryCapability.AGGREGATE_TEMPORAL) != null)
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = GreaterThanOrEqualFilter(QueryField("state.createdAt"), T0 + DAY),
                groupBy = listOf(AggregationGroup.DateHistogram(QueryField("state.createdAt"), "day", AggregationDateUnit.DAY)),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        rows.map { it.path("day").longValue() }.assert().containsExactly(T0 + DAY, T0 + 2 * DAY, T0 + 3 * DAY, T0 + 4 * DAY)
        Unit
    }

    // Timeouts (decision D150)

    @Test
    fun `an expired timeout fails every primitive with the timeout error`() = blocking {
        val fixture = given(orders())
        val expired = Duration.ofNanos(1)
        suspend fun fails(block: suspend () -> Any?) {
            val error = runCatching { block() }.exceptionOrNull()
            (error is QueryTimeoutException).assert().describedAs("$error").isTrue()
            (error as QueryTimeoutException).errorCode.assert().isEqualTo(QueryErrors.TIMEOUT)
        }
        fails { fixture.backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), fixture.schema).withTimeout(expired)).toList() }
        fails { fixture.backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), fixture.schema).withTimeout(expired)) }
        fails {
            fixture.backend.countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), fixture.schema).withTimeout(expired))
        }
        fails {
            val aggregation = AggregationQuery(MatchAllFilter, metrics = listOf(AggregationMetric.Count("count")))
            fixture.backend.aggregate(QueryAdmission.Trusted.aggregate(aggregation, fixture.schema).withTimeout(expired)).toList()
        }
    }

    @Test
    fun `a query within its timeout runs as without one`() = blocking {
        val fixture = given(orders())
        val admitted: AdmittedQuery<me.ahoo.wow.api.query.ICountQuery> =
            QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), fixture.schema).withTimeout(GENEROUS)
        admitted.timeout.assert().isEqualTo(GENEROUS)
        fixture.backend.countBounded(admitted).assert().isEqualTo(CountResult(5, exact = true))
        Unit
    }

    // The gateway over the backend

    @Test
    fun `the gateway admits snapshot queries with the active default scope`() = blocking {
        val fixture = given(orders())
        val gateway = DefaultQueryGateway(type, fixture.backend, FixedQueryModelSchemaProvider(fixture.schema))
        gateway.list(ListQuery(MatchAllFilter, sort = listOf(Sort(AMOUNT, Sort.Direction.ASC))), QueryCaller.IN_PROCESS)
            .toList().map { it.id() }.assert().containsExactly("o1", "o2", "o3", "o5")
        gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS).assert().isEqualTo(CountResult(4, exact = true))
        gateway.single(SingleQuery(AggregateIdFilter("o4")), QueryCaller.IN_PROCESS).assert().isNull()
        Unit
    }

    @Test
    fun `the gateway bounds counts and totals by the entry's budget`() = blocking {
        val fixture = given(orders())
        val policy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 2, timeout = GENEROUS))
        val gateway = DefaultQueryGateway(type, fixture.backend, FixedQueryModelSchemaProvider(fixture.schema), entryPolicy = policy)
        val http = QueryCaller(QueryEntry.HTTP)
        gateway.count(CountQuery(MatchAllFilter), http).assert().isEqualTo(CountResult(2, exact = false))
        gateway.paged(PagedQuery(MatchAllFilter, pagination = Pagination(1, 1)), http).let { page ->
            page.list.assert().hasSize(1)
            page.total.assert().isEqualTo(2L)
            page.exact.assert().isFalse()
        }
        val exact = runCatching { gateway.paged(PagedQuery(MatchAllFilter, total = TotalMode.EXACT), http) }.exceptionOrNull()
        (exact as QueryRequestException).code.assert().isEqualTo(me.ahoo.wow.api.query.QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED)
        Unit
    }

    private fun numeric(function: AggregationFunction, alias: String) =
        AggregationMetric.Numeric(function, AggregationExpression.Field(AMOUNT), alias)

    companion object {
        private const val CONTEXT_SUFFIX = 12
        private const val MAX_LIST = 100
        const val TENANT: String = "tenant"
        const val OTHER_TENANT: String = "other-tenant"

        /** 2026-01-05T00:00:00Z, a Monday. */
        val T0: Long = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
        const val DAY: Long = 86_400_000L
        private val AMOUNT = QueryField("state.amount")
        private val GENEROUS: Duration = Duration.ofSeconds(30)

        /** The state field each semantic shape's probes are stored in. */
        val PROBE_FIELDS: Map<SemanticShape, String> = mapOf(
            SemanticShape.STRING to "state.probeText",
            SemanticShape.NUMBER to "state.probeNumber",
            SemanticShape.STRING_ARRAY to "state.probeTags",
        )

        fun probeId(probe: SemanticProbe): String = "semantic-${probe.shape.name.lowercase()}-${probe.name}"

        /** The fields of the order state the TCK queries, as the Manifest would declare them. */
        val DECLARATION = querySchema {
            field("state.name") { types(QueryValueType.STRING) }
            field("state.status") { types(QueryValueType.STRING) }
            field("state.amount") { types(QueryValueType.DECIMAL) }
            field("state.quantity") { types(QueryValueType.INTEGER) }
            field("state.createdAt") {
                types(QueryValueType.INTEGER)
                temporalEpoch()
            }
            field("state.keywords") { items { types(QueryValueType.STRING) } }
            field("state.lines") {
                items {
                    kind(QueryValueKind.OBJECT)
                    property("sku") { types(QueryValueType.STRING) }
                    property("price") { types(QueryValueType.DECIMAL) }
                    property("quantity") { types(QueryValueType.INTEGER) }
                }
            }
            field("state.probeText") { types(QueryValueType.STRING) }
            field("state.probeNumber") { types(QueryValueType.INTEGER) }
            field("state.probeTags") { items { types(QueryValueType.STRING) } }
        }

        private fun ObjectNode.id(): String = path("aggregateId").stringValue()

    }
}
