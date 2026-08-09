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

package me.ahoo.wow.elasticsearch.query.planned

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.BackendSort
import me.ahoo.wow.query.backend.BackendSortOrigin
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
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
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class ElasticsearchRecordQueryCompilerTest {
    private val compiler: ElasticsearchRecordQueryCompiler
        get() = ElasticsearchRecordQueryCompiler(binding)

    @Test
    fun `compiler should bind enforced exact filter projection and stable sort roles`() {
        val user = predicate(name, PredicateOperator.EQ, NormalizedValue.Text("alice"))
        val mandatory = predicate(tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1"))
        val compiled = compiler.compile(
            streamPlan(
                BackendEnforcedFilter(user, mandatory),
                BackendProjection.Include(listOf(state, name)),
                listOf(BackendSort(identity, NormalizedSortDirection.ASC, BackendSortOrigin.STABILITY_TIE_BREAKER)),
            ),
        )

        val json = compiled.query.toString()
        json.assert().contains("state.name.exact", MessageRecords.TENANT_ID, "alice", "tenant-1")
        compiled.sourceFilter!!.includes().assert().containsExactly(MessageRecords.AGGREGATE_ID, "state")
        compiled.sort.assert().hasSize(1)
        compiled.sort.single().field().field().assert().isEqualTo(MessageRecords.AGGREGATE_ID)
        compiled.limit.assert().isEqualTo(10)
    }

    @Test
    fun `compiler should preserve literal wildcard text and use explicit search scope`() {
        val literal = compiler.compileCondition(
            predicate(name, PredicateOperator.CONTAINS, NormalizedValue.Text("a*?\\b")),
        )
        literal.wildcard().field().assert().isEqualTo("state.name.exact")
        literal.wildcard().value().assert().isEqualTo("*a\\*\\?\\\\b*")
        literal.wildcard().caseInsensitive().assert().isFalse()

        val search = compiler.compileCondition(BackendPlannedCondition.Search(scope, "search text"))
        search.match().field().assert().isEqualTo("state.name")
        search.match().query().stringValue().assert().isEqualTo("search text")
    }

    @Test
    fun `compiler should preserve Mongo null missing baseline where Elasticsearch can prove equivalence`() {
        val isNull = compiler.compileCondition(predicate(name, PredicateOperator.EQ, NormalizedValue.Null))
        isNull.bool().mustNot().single().exists().field().assert().isEqualTo("state.name.exact")

        val isNotNull = compiler.compileCondition(predicate(name, PredicateOperator.NE, NormalizedValue.Null))
        isNotNull.bool().mustNot().single().bool().mustNot().single().exists().field().assert()
            .isEqualTo("state.name.exact")

        val includesNull = compiler.compileCondition(
            predicate(
                name,
                PredicateOperator.IN,
                NormalizedValue.ListValue(listOf(NormalizedValue.Text("alice"), NormalizedValue.Null)),
            ),
        )
        includesNull.bool().should().assert().hasSize(2)

        val exists = compiler.compileCondition(
            predicate(name, PredicateOperator.EXISTS, NormalizedValue.BooleanValue(true)),
        )
        exists.term().field().assert().isEqualTo("state.name.present")
        exists.term().value().booleanValue().assert().isTrue()
        val missing = compiler.compileCondition(
            predicate(name, PredicateOperator.EXISTS, NormalizedValue.BooleanValue(false)),
        )
        missing.bool().mustNot().single().term().field().assert().isEqualTo("state.name.present")

        val excludesNull = compiler.compileCondition(
            predicate(
                name,
                PredicateOperator.NOT_IN,
                NormalizedValue.ListValue(listOf(NormalizedValue.Null)),
            ),
        )
        excludesNull.bool().should().assert().hasSize(2)
    }

    @Test
    fun `compiler should require nested mapping role and reject insensitive or native input`() {
        val nested = compiler.compileCondition(
            BackendPlannedCondition.ElementMatch(
                items,
                predicate(itemName, PredicateOperator.EQ, NormalizedValue.Text("item")),
            ),
        )
        nested.nested().path().assert().isEqualTo("state.items")
        nested.nested().query().term().field().assert().isEqualTo("state.items.name")

        assertUnsupported {
            compiler.compileCondition(
                BackendPlannedCondition.Predicate(
                    name,
                    PredicateOperator.EQ,
                    NormalizedValue.Text("ALICE"),
                    NormalizedPredicateOptions(CaseSensitivity.INSENSITIVE),
                ),
            )
        }
    }

    private fun streamPlan(
        filter: BackendEnforcedFilter,
        projection: BackendProjection,
        sort: List<BackendSort>,
    ) = BackendStreamQueryPlan(
        target,
        schema.contractId,
        filter,
        RecordResultShape.DYNAMIC,
        projection,
        sort,
        10,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("8".repeat(64)),
    )

    private fun predicate(
        field: QueryFieldId,
        operator: PredicateOperator,
        value: NormalizedValue? = null,
    ) = BackendPlannedCondition.Predicate(field, operator, value)

    private fun assertUnsupported(action: () -> Unit) {
        assertThrownBy<QueryBackendException>(action).satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(QueryBackendFailureKind.UNSUPPORTED) },
        )
    }

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val tenant = QueryFieldId.System(SystemFieldKind.TENANT_ID)
    private val state = QueryFieldId.Path(listOf("state"))
    private val name = QueryFieldId.Path(listOf("state", "name"))
    private val items = QueryFieldId.Path(listOf("state", "items"))
    private val itemName = QueryFieldId.Path(listOf("state", "items", "name"))
    private val scope = SearchScopeId("state-name")
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            field(identity, LogicalFieldType.Text, setOf(PredicateOperator.EQ), EXACT_SORT_PROJECT),
            field(tenant, LogicalFieldType.Text, setOf(PredicateOperator.EQ), setOf(FieldCapability.EXACT)),
            field(state, LogicalFieldType.Object),
            field(
                name,
                LogicalFieldType.Text,
                setOf(
                    PredicateOperator.EQ,
                    PredicateOperator.NE,
                    PredicateOperator.IN,
                    PredicateOperator.NOT_IN,
                    PredicateOperator.CONTAINS,
                ),
                setOf(
                    FieldCapability.EXACT,
                    FieldCapability.PRESENCE,
                    FieldCapability.FULL_TEXT,
                    FieldCapability.LITERAL_PATTERN,
                    FieldCapability.SORTABLE,
                    FieldCapability.PROJECTABLE,
                    FieldCapability.AGGREGATABLE,
                ),
            ),
            field(
                items,
                LogicalFieldType.Array(
                    LogicalFieldType.Object,
                    Nullability.NON_NULL,
                    EmptyArraySemantics.DISTINCT,
                ),
                capabilities = setOf(FieldCapability.ELEMENT_MATCH),
            ),
            field(itemName, LogicalFieldType.Text, setOf(PredicateOperator.EQ), setOf(FieldCapability.EXACT)),
        ),
        listOf(QuerySearchScopeDefinition(scope, null, listOf(name), listOf(name))),
    )
    private val binding = ElasticsearchSnapshotQueryBinding(
        schema,
        "wow.sales.order.snapshot",
        "order-query-v1",
        linkedMapOf(
            identity to ElasticsearchFieldBinding(
                MessageRecords.AGGREGATE_ID,
                EXACT_SORT_PROJECT,
                exactField = "_id",
                sortField = MessageRecords.AGGREGATE_ID,
                keywordReadiness = KEYWORD_READINESS,
            ),
            tenant to ElasticsearchFieldBinding(
                MessageRecords.TENANT_ID,
                setOf(FieldCapability.EXACT),
                exactField = MessageRecords.TENANT_ID,
                keywordReadiness = KEYWORD_READINESS,
            ),
            state to ElasticsearchFieldBinding("state", emptySet()),
            name to ElasticsearchFieldBinding(
                "state.name",
                setOf(
                    FieldCapability.EXACT,
                    FieldCapability.FULL_TEXT,
                    FieldCapability.LITERAL_PATTERN,
                    FieldCapability.SORTABLE,
                    FieldCapability.PROJECTABLE,
                    FieldCapability.AGGREGATABLE,
                ),
                exactField = "state.name.exact",
                presenceField = "state.name.present",
                searchField = "state.name",
                searchAnalyzer = "standard",
                literalField = "state.name.exact",
                sortField = "state.name.exact",
                groupField = "state.name.exact",
                groupReadiness = GROUP_READINESS,
                keywordReadiness = KEYWORD_READINESS,
            ),
            items to ElasticsearchFieldBinding(
                "state.items",
                setOf(FieldCapability.ELEMENT_MATCH),
                nestedPath = "state.items",
            ),
            itemName to ElasticsearchFieldBinding(
                "state.items.name",
                setOf(FieldCapability.EXACT),
                exactField = "state.items.name",
                keywordReadiness = KEYWORD_READINESS,
            ),
        ),
        listOf(ElasticsearchSearchScopeBinding(scope, mapOf(name to "state.name"))),
    )

    private fun field(
        id: QueryFieldId,
        type: LogicalFieldType,
        operators: Set<PredicateOperator> = emptySet(),
        capabilities: Set<FieldCapability> = emptySet(),
    ) = QueryFieldSchema(id, type, Presence.OPTIONAL, Nullability.NULLABLE, operators, capabilities)

    private companion object {
        val KEYWORD_READINESS = ElasticsearchKeywordReadiness(128, 512, true, true)
        val GROUP_READINESS = ElasticsearchGroupReadiness(historicalValuesAudited = true)
        val EXACT_SORT_PROJECT = setOf(
            FieldCapability.EXACT,
            FieldCapability.SORTABLE,
            FieldCapability.PROJECTABLE,
        )
    }
}
