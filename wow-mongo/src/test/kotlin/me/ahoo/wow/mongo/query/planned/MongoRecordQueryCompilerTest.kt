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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.mongo.query.planned

import com.mongodb.MongoNamespace
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendPageWindow
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.BackendRequiredConsistency
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendSort
import me.ahoo.wow.query.backend.BackendSortOrigin
import me.ahoo.wow.query.backend.BackendTotalMode
import me.ahoo.wow.query.backend.CaseSensitivity
import me.ahoo.wow.query.backend.EmptyArraySemantics
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedPredicateOptions
import me.ahoo.wow.query.backend.NormalizedSortDirection
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.QuerySearchScopeDefinition
import me.ahoo.wow.query.backend.RecordResultShape
import me.ahoo.wow.query.backend.SearchScopeId
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.Decimal128
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.function.Consumer

class MongoRecordQueryCompilerTest {
    private val fixture = Fixture()
    private val compiler = MongoRecordQueryCompiler(fixture.binding)

    @Test
    fun `should compile user and mandatory predicates from logical binding`() {
        val plan = fixture.plan(
            user = fixture.predicate(fixture.name, PredicateOperator.EQ, NormalizedValue.Text("Alice")),
            mandatory = fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1")),
        )

        compiler.compile(plan).filter.toBsonDocument().assert().isEqualTo(
            Filters.and(
                Filters.eq("state.name", "Alice"),
                Filters.eq(MessageRecords.TENANT_ID, "tenant-1"),
            ).toBsonDocument(),
        )
    }

    @Test
    fun `should make element match child path relative to current element`() {
        val element = BackendPlannedCondition.ElementMatch(
            fixture.items,
            fixture.predicate(fixture.itemName, PredicateOperator.EQ, NormalizedValue.Text("book")),
        )

        compiler.compile(fixture.plan(element)).filter.toBsonDocument().assert().isEqualTo(
            Filters.and(
                Filters.elemMatch("state.items", Filters.eq("name", "book")),
                Filters.empty(),
            ).toBsonDocument(),
        )
    }

    @Test
    fun `should preserve literal regex and encode instant as epoch millis`() {
        val condition = BackendPlannedCondition.Junction(
            me.ahoo.wow.query.backend.JunctionOperator.AND,
            listOf(
                fixture.predicate(fixture.name, PredicateOperator.CONTAINS, NormalizedValue.Text("a.*?\\b")),
                fixture.predicate(
                    fixture.createdAt,
                    PredicateOperator.GTE,
                    NormalizedValue.InstantValue(Instant.ofEpochMilli(1234)),
                ),
            ),
        )

        compiler.compile(fixture.plan(condition)).filter.toBsonDocument().assert().isEqualTo(
            Filters.and(
                Filters.and(
                    Filters.regex("state.name", "a\\.\\*\\?\\\\b"),
                    Filters.gte("state.createdAt", 1234L),
                ),
                Filters.empty(),
            ).toBsonDocument(),
        )
    }

    @Test
    fun `should encode Decimal128 exactly and reject out of range decimal before storage`() {
        compiler.compile(
            fixture.plan(
                user = fixture.predicate(
                    fixture.amount,
                    PredicateOperator.EQ,
                    NormalizedValue.Decimal(BigDecimal("1.00")),
                ),
            ),
        ).filter.toBsonDocument().assert().isEqualTo(
            Filters.and(Filters.eq("state.amount", Decimal128(BigDecimal.ONE)), Filters.empty()).toBsonDocument(),
        )

        assertThrownBy<QueryBackendException> {
            compiler.compile(
                fixture.plan(
                    user = fixture.predicate(
                        fixture.amount,
                        PredicateOperator.EQ,
                        NormalizedValue.Decimal(BigDecimal("1E+7000")),
                    ),
                ),
            )
        }.satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(QueryBackendFailureKind.UNSUPPORTED) },
        )
    }

    @Test
    fun `should force identity fetch while preserving logical projection and sort`() {
        val plan = fixture.plan(
            projection = BackendProjection.Include(listOf(fixture.name)),
            sort = listOf(
                BackendSort(fixture.name, NormalizedSortDirection.DESC, BackendSortOrigin.USER),
                BackendSort(fixture.identity, NormalizedSortDirection.ASC, BackendSortOrigin.STABILITY_TIE_BREAKER),
            ),
        )
        val compiled = compiler.compile(plan)

        compiled.projection?.toBsonDocument().assert().isEqualTo(
            Projections.include(listOf("state.name", Documents.ID_FIELD)).toBsonDocument(),
        )
        compiled.sort?.toBsonDocument().assert().isEqualTo(
            Sorts.orderBy(Sorts.descending("state.name"), Sorts.ascending(Documents.ID_FIELD)).toBsonDocument(),
        )
    }

    @Test
    fun `should canonicalize ancestor projection before creating Mongo projection`() {
        val compiled = compiler.compile(
            fixture.plan(projection = BackendProjection.Include(listOf(fixture.state, fixture.name))),
        )

        compiled.projection?.toBsonDocument().assert().isEqualTo(
            Projections.include(listOf("state", Documents.ID_FIELD)).toBsonDocument(),
        )
    }

    @Test
    fun `should preserve one exact same-input page window for the backend`() {
        val compiled = compiler.compile(fixture.pagePlan(offset = 3, size = 2))

        compiled.page.assert().isEqualTo(BackendPageWindow(3, 2))
        (compiled.limit == null).assert().isTrue()
        val pipeline = compiled.pagePipeline().map(Bson::toBsonDocument)
        pipeline.none { stage -> stage.containsKey("\$facet") }.assert().isTrue()
        pipeline.last().containsKey("\$unionWith").assert().isTrue()
    }

    @Test
    fun `page result should keep records separate from the exact total row`() {
        val results = listOf(
            Document(Documents.ID_FIELD, "order-1")
                .append(MessageRecords.TENANT_ID, "tenant-1")
                .append(StateAggregateRecords.DELETED, false)
                .append("state", Document("name", "Alice")),
            Document(MongoCompiledRecordQuery.PAGE_TOTAL_VALUE, 3L),
        )

        val page = MongoPageResultMapper(fixture.binding.prepared).map(results, BackendProjection.All)

        page.total.assert().isEqualTo(3)
        page.records.single().identity.assert().isEqualTo("order-1")
        page.records.single().document.values["state"].assert().isEqualTo(
            NormalizedValue.ObjectValue(mapOf("name" to NormalizedValue.Text("Alice"))),
        )
    }

    @Test
    fun `should reject insensitive predicate before storage`() {
        val predicate = BackendPlannedCondition.Predicate(
            fixture.name,
            PredicateOperator.EQ,
            NormalizedValue.Text("Alice"),
            NormalizedPredicateOptions(CaseSensitivity.INSENSITIVE),
        )

        assertThrownBy<QueryBackendException> {
            compiler.compile(fixture.plan(predicate))
        }
    }

    @Test
    fun `should compile one attested root text scope`() {
        val search = BackendPlannedCondition.Search(fixture.searchScope, "paid order")

        compiler.compile(fixture.plan(user = search)).filter.toBsonDocument().assert().isEqualTo(
            Filters.and(Filters.text("paid order"), Filters.empty()).toBsonDocument(),
        )
    }

    @Test
    fun `should reject text search below non-conjunctive structure`() {
        val search = BackendPlannedCondition.Search(fixture.searchScope, "paid")
        val disjunction = BackendPlannedCondition.Junction(
            me.ahoo.wow.query.backend.JunctionOperator.OR,
            listOf(search, fixture.predicate(fixture.name, PredicateOperator.EQ, NormalizedValue.Text("Alice"))),
        )

        assertThrownBy<QueryBackendException> {
            compiler.compile(fixture.plan(user = disjunction))
        }
    }

    @TestFactory
    fun `should compile every portable predicate operator`(): List<DynamicTest> = predicateCases().map { case ->
        DynamicTest.dynamicTest(case.operator.name) {
            val actual = compiler.compile(
                fixture.plan(user = fixture.predicate(case.field, case.operator, case.value)),
            ).filter
            actual.toBsonDocument().assert().isEqualTo(
                Filters.and(case.expected, Filters.empty()).toBsonDocument(),
            )
        }
    }

    private fun predicateCases(): List<PredicateCase> {
        val text = NormalizedValue.Text("Alice")
        val texts = NormalizedValue.ListValue(listOf(NormalizedValue.Text("Alice"), NormalizedValue.Text("Bob")))
        val instants = NormalizedValue.ListValue(listOf(instant(10), instant(20)))
        return listOf(
            PredicateCase(fixture.name, PredicateOperator.EQ, text, Filters.eq("state.name", "Alice")),
            PredicateCase(fixture.name, PredicateOperator.NE, text, Filters.ne("state.name", "Alice")),
            PredicateCase(fixture.createdAt, PredicateOperator.GT, instant(10), Filters.gt("state.createdAt", 10L)),
            PredicateCase(fixture.createdAt, PredicateOperator.LT, instant(10), Filters.lt("state.createdAt", 10L)),
            PredicateCase(fixture.createdAt, PredicateOperator.GTE, instant(10), Filters.gte("state.createdAt", 10L)),
            PredicateCase(fixture.createdAt, PredicateOperator.LTE, instant(10), Filters.lte("state.createdAt", 10L)),
            PredicateCase(fixture.name, PredicateOperator.CONTAINS, text, Filters.regex("state.name", "Alice")),
            PredicateCase(
                fixture.name,
                PredicateOperator.IN,
                texts,
                Filters.`in`("state.name", listOf("Alice", "Bob")),
            ),
            PredicateCase(
                fixture.name,
                PredicateOperator.NOT_IN,
                texts,
                Filters.nin("state.name", listOf("Alice", "Bob")),
            ),
            PredicateCase(
                fixture.createdAt,
                PredicateOperator.BETWEEN,
                instants,
                Filters.and(Filters.gte("state.createdAt", 10L), Filters.lte("state.createdAt", 20L)),
            ),
            PredicateCase(
                fixture.tags,
                PredicateOperator.ALL_IN,
                texts,
                Filters.all("state.tags", listOf("Alice", "Bob")),
            ),
            PredicateCase(fixture.name, PredicateOperator.STARTS_WITH, text, Filters.regex("state.name", "^Alice")),
            PredicateCase(fixture.name, PredicateOperator.ENDS_WITH, text, Filters.regex("state.name", "Alice$")),
            PredicateCase(fixture.name, PredicateOperator.IS_NULL, null, Filters.eq("state.name", null)),
            PredicateCase(fixture.name, PredicateOperator.NOT_NULL, null, Filters.ne("state.name", null)),
            PredicateCase(fixture.active, PredicateOperator.IS_TRUE, null, Filters.eq("state.active", true)),
            PredicateCase(fixture.active, PredicateOperator.IS_FALSE, null, Filters.eq("state.active", false)),
            PredicateCase(
                fixture.name,
                PredicateOperator.EXISTS,
                NormalizedValue.BooleanValue(true),
                Filters.exists("state.name", true),
            ),
        )
    }

    private fun instant(epochMilli: Long) = NormalizedValue.InstantValue(Instant.ofEpochMilli(epochMilli))

    private class Fixture {
        val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
        val tenant = QueryFieldId.System(SystemFieldKind.TENANT_ID)
        private val deleted = QueryFieldId.System(SystemFieldKind.DELETED)
        val state = QueryFieldId.Path(listOf("state"))
        val name = QueryFieldId.Path(listOf("state", "name"))
        val createdAt = QueryFieldId.Path(listOf("state", "createdAt"))
        val amount = QueryFieldId.Path(listOf("state", "amount"))
        val active = QueryFieldId.Path(listOf("state", "active"))
        val tags = QueryFieldId.Path(listOf("state", "tags"))
        val items = QueryFieldId.Path(listOf("state", "items"))
        val itemName = QueryFieldId.Path(listOf("state", "items", "name"))
        private val description = QueryFieldId.Path(listOf("description"))
        val searchScope = SearchScopeId("document-text")
        private val target = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
        private val schema = QueryDocumentSchema(
            target,
            listOf(
                field(identity, LogicalFieldType.Text, setOf(PredicateOperator.EQ), EXACT_SORT_PROJECT),
                field(tenant, LogicalFieldType.Text, setOf(PredicateOperator.EQ), setOf(FieldCapability.EXACT)),
                field(
                    deleted,
                    LogicalFieldType.Boolean,
                    setOf(PredicateOperator.IS_FALSE),
                    setOf(FieldCapability.EXACT)
                ),
                field(state, LogicalFieldType.Object),
                field(
                    name,
                    LogicalFieldType.Text,
                    setOf(
                        PredicateOperator.EQ,
                        PredicateOperator.NE,
                        PredicateOperator.CONTAINS,
                        PredicateOperator.IN,
                        PredicateOperator.NOT_IN,
                        PredicateOperator.STARTS_WITH,
                        PredicateOperator.ENDS_WITH,
                        PredicateOperator.IS_NULL,
                        PredicateOperator.NOT_NULL,
                        PredicateOperator.EXISTS,
                    ),
                    setOf(
                        FieldCapability.EXACT,
                        FieldCapability.PRESENCE,
                        FieldCapability.LITERAL_PATTERN,
                        FieldCapability.SORTABLE,
                        FieldCapability.PROJECTABLE,
                    ),
                ),
                field(
                    createdAt,
                    LogicalFieldType.Instant,
                    setOf(
                        PredicateOperator.GT,
                        PredicateOperator.LT,
                        PredicateOperator.GTE,
                        PredicateOperator.LTE,
                        PredicateOperator.BETWEEN,
                    ),
                    setOf(FieldCapability.RANGE),
                ),
                field(
                    amount,
                    LogicalFieldType.Decimal,
                    setOf(PredicateOperator.EQ),
                    setOf(FieldCapability.EXACT),
                ),
                field(
                    active,
                    LogicalFieldType.Boolean,
                    setOf(PredicateOperator.IS_TRUE, PredicateOperator.IS_FALSE),
                    setOf(FieldCapability.EXACT),
                ),
                field(
                    tags,
                    LogicalFieldType.Array(
                        LogicalFieldType.Text,
                        Nullability.NON_NULL,
                        EmptyArraySemantics.DISTINCT,
                    ),
                    setOf(PredicateOperator.ALL_IN),
                    setOf(FieldCapability.EXACT),
                ),
                field(
                    items,
                    LogicalFieldType.Array(LogicalFieldType.Object, Nullability.NON_NULL, EmptyArraySemantics.DISTINCT),
                    capabilities = setOf(FieldCapability.ELEMENT_MATCH),
                ),
                field(itemName, LogicalFieldType.Text, setOf(PredicateOperator.EQ), setOf(FieldCapability.EXACT)),
                field(description, LogicalFieldType.Text, capabilities = setOf(FieldCapability.FULL_TEXT)),
            ),
            listOf(QuerySearchScopeDefinition(searchScope, null, listOf(description), listOf(description))),
        )
        val binding = MongoSnapshotQueryBinding(
            schema,
            MongoNamespace("sales", "order_snapshot"),
            linkedMapOf(
                identity to MongoFieldBinding(Documents.ID_FIELD, EXACT_SORT_PROJECT),
                tenant to MongoFieldBinding(MessageRecords.TENANT_ID, setOf(FieldCapability.EXACT)),
                deleted to MongoFieldBinding(StateAggregateRecords.DELETED, setOf(FieldCapability.EXACT)),
                state to MongoFieldBinding("state", emptySet()),
                name to MongoFieldBinding(
                    "state.name",
                    setOf(
                        FieldCapability.EXACT,
                        FieldCapability.PRESENCE,
                        FieldCapability.LITERAL_PATTERN,
                        FieldCapability.SORTABLE,
                        FieldCapability.PROJECTABLE,
                    ),
                ),
                createdAt to MongoFieldBinding("state.createdAt", setOf(FieldCapability.RANGE), MongoValueEncoding.EPOCH_MILLIS),
                amount to MongoFieldBinding(
                    "state.amount",
                    setOf(FieldCapability.EXACT),
                    MongoValueEncoding.DECIMAL128,
                ),
                active to MongoFieldBinding("state.active", setOf(FieldCapability.EXACT)),
                tags to MongoFieldBinding("state.tags", setOf(FieldCapability.EXACT)),
                items to MongoFieldBinding("state.items", setOf(FieldCapability.ELEMENT_MATCH)),
                itemName to MongoFieldBinding("state.items.name", setOf(FieldCapability.EXACT)),
                description to MongoFieldBinding("description", setOf(FieldCapability.FULL_TEXT)),
            ),
            textSearch = MongoTextSearchBinding(searchScope, "description_text"),
        )

        fun predicate(
            field: QueryFieldId,
            operator: PredicateOperator,
            value: NormalizedValue? = null,
        ) = BackendPlannedCondition.Predicate(field, operator, value)

        fun plan(
            user: BackendPlannedCondition = BackendPlannedCondition.All,
            mandatory: BackendPlannedCondition = BackendPlannedCondition.All,
            projection: BackendProjection = BackendProjection.All,
            sort: List<BackendSort> = emptyList(),
        ) = BackendSingleQueryPlan(
            target,
            schema.contractId,
            BackendEnforcedFilter(user, mandatory),
            RecordResultShape.DYNAMIC,
            projection,
            sort,
            BackendRequiredCapabilities(),
            SemanticTier.PORTABLE,
            PlanFingerprint("0".repeat(64)),
        )

        fun pagePlan(offset: Long, size: Int) = BackendPageQueryPlan(
            target,
            schema.contractId,
            BackendEnforcedFilter(BackendPlannedCondition.All, BackendPlannedCondition.All),
            RecordResultShape.DYNAMIC,
            BackendProjection.All,
            listOf(BackendSort(identity, NormalizedSortDirection.ASC, BackendSortOrigin.STABILITY_TIE_BREAKER)),
            BackendPageWindow(offset, size),
            BackendTotalMode.EXACT,
            BackendRequiredConsistency.SAME_INPUT,
            BackendRequiredCapabilities(),
            SemanticTier.PORTABLE,
            PlanFingerprint("3".repeat(64)),
        )

        private fun field(
            id: QueryFieldId,
            type: LogicalFieldType,
            operators: Set<PredicateOperator> = emptySet(),
            capabilities: Set<FieldCapability> = emptySet(),
        ) = QueryFieldSchema(id, type, Presence.OPTIONAL, Nullability.NULLABLE, operators, capabilities)

        companion object {
            val EXACT_SORT_PROJECT = setOf(
                FieldCapability.EXACT,
                FieldCapability.SORTABLE,
                FieldCapability.PROJECTABLE,
            )
        }
    }

    private data class PredicateCase(
        val field: QueryFieldId,
        val operator: PredicateOperator,
        val value: NormalizedValue?,
        val expected: Bson,
    )
}
