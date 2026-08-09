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

package me.ahoo.wow.query.internal.planning

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.CaseSensitivity
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope
import me.ahoo.wow.query.internal.normalization.NormalizedPredicateOptions
import me.ahoo.wow.query.internal.normalization.NormalizedProjection
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.NormalizedSortDirection
import me.ahoo.wow.query.internal.normalization.PathBasis
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SearchScopeId
import me.ahoo.wow.query.internal.plan.CountQueryPlan
import me.ahoo.wow.query.internal.plan.PageQueryPlan
import me.ahoo.wow.query.internal.plan.PlannedCondition
import me.ahoo.wow.query.internal.plan.PlannedProjection
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.plan.SingleQueryPlan
import me.ahoo.wow.query.internal.plan.StreamLimit
import me.ahoo.wow.query.internal.plan.StreamQueryPlan
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class QueryPlannerRecordTest {

    private val planner = QueryPlanner()

    @Test
    fun `planner should create all record operation plans and preserve zero as unbounded`() {
        planner.plan(
            PlanningFixtures.single(),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.STRICT),
        ).planned().plan.assert().isInstanceOf(SingleQueryPlan::class.java)

        val stream = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.STREAM,
            QueryResultShape.DYNAMIC,
            NormalizedQueryInput.Stream(PlanningFixtures.recordQuery(), limit = 0),
        )
        val streamPlan = planner.plan(stream, PlanningFixtures.schema, constraints(QueryValidationMode.STRICT))
            .planned().plan as StreamQueryPlan
        streamPlan.limit.assert().isEqualTo(StreamLimit.Unbounded)

        val bounded = stream.copy(input = NormalizedQueryInput.Stream(PlanningFixtures.recordQuery(), limit = 25))
        (
            planner.plan(bounded, PlanningFixtures.schema, constraints(QueryValidationMode.STRICT)).planned().plan
                as StreamQueryPlan
            ).limit.assert().isEqualTo(StreamLimit.Bounded(25))

        val count = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.COUNT,
            QueryResultShape.COUNT,
            NormalizedQueryInput.Count(NormalizedCondition.All, NormalizedDeletionScope.EXPLICIT),
        )
        planner.plan(count, PlanningFixtures.schema, constraints(QueryValidationMode.STRICT))
            .planned().plan.operation.assert().isEqualTo(QueryOperation.COUNT)
    }

    @Test
    fun `planner should apply default active deletion without treating it as user field access`() {
        val defaultActive = PlanningFixtures.recordQuery(
            deletionScope = NormalizedDeletionScope.DEFAULT_ACTIVE,
        )
        val constrained = constraints(QueryValidationMode.STRICT).copy(
            fieldConstraint = QueryFieldConstraint(filterFields = FieldAccess.DenyAll),
        )
        val recordPlan = planner.plan(
            PlanningFixtures.single(defaultActive),
            PlanningFixtures.schema,
            constrained,
        ).planned().plan as SingleQueryPlan
        recordPlan.filter.user.assert().isEqualTo(
            PlannedCondition.Predicate(PlanningFixtures.deleted, PredicateOperator.IS_FALSE),
        )

        val countPlan = planner.plan(
            NormalizedQueryInvocation(
                PlanningFixtures.target,
                QueryOperation.COUNT,
                QueryResultShape.COUNT,
                NormalizedQueryInput.Count(NormalizedCondition.All, NormalizedDeletionScope.DEFAULT_ACTIVE),
            ),
            PlanningFixtures.schema,
            constrained,
        ).planned().plan as CountQueryPlan
        countPlan.filter.user.assert().isEqualTo(recordPlan.filter.user)

        val explicitAll = PlanningFixtures.recordQuery(
            deletionScope = NormalizedDeletionScope.EXPLICIT,
        )
        val explicitPlan = planner.plan(
            PlanningFixtures.single(explicitAll),
            PlanningFixtures.schema,
            constrained,
        ).planned().plan as SingleQueryPlan
        explicitPlan.filter.user.assert().isEqualTo(PlannedCondition.All)
    }

    @Test
    fun `compatible page should not silently change legacy ordering`() {
        val page = planner.plan(
            PlanningFixtures.page(),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.COMPATIBLE),
        ).planned().plan as PageQueryPlan

        page.sort.assert().isEmpty()
    }

    @Test
    fun `duplicate sort should reject in strict and fallback in compatible mode`() {
        val field = PlanningFixtures.path("state", "amount")
        val query = PlanningFixtures.recordQuery(
            sort = listOf(
                PlanningFixtures.sort(field),
                PlanningFixtures.sort(field, NormalizedSortDirection.DESC),
            ),
        )

        assertRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.DUPLICATE_SORT,
            "$.input.query.sort[1].field",
        ) {
            planner.plan(
                PlanningFixtures.page(query),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.STRICT),
            )
        }
        planner.plan(
            PlanningFixtures.page(query),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.COMPATIBLE),
        ).assert().isInstanceOf(PlanningDecision.LegacyFallback::class.java)
    }

    @Test
    fun `planner should preserve mandatory provenance in plan and fallback`() {
        val user = predicate(PlanningFixtures.path("state", "name"), PredicateOperator.EQ, NormalizedValue.Text("Ada"))
        val mandatory = predicate(
            LogicalField.System(me.ahoo.wow.query.internal.normalization.SystemFieldKind.TENANT_ID),
            PredicateOperator.EQ,
            NormalizedValue.Text("tenant-1"),
        )
        val planned = planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(user)),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.STRICT, mandatory),
        ).planned().plan as SingleQueryPlan

        planned.filter.user.assert().isEqualTo(
            PlannedCondition.Predicate(PlanningFixtures.name, PredicateOperator.EQ, NormalizedValue.Text("Ada")),
        )
        planned.filter.mandatory.assert().isEqualTo(
            PlannedCondition.Predicate(PlanningFixtures.tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1")),
        )
        (planned.filter.condition as PlannedCondition.Junction).children.values.assert().containsExactly(
            planned.filter.user,
            planned.filter.mandatory,
        )

        val compatibleGap = PlanningFixtures.recordQuery(
            predicate(
                PlanningFixtures.path("state", "unknown"),
                PredicateOperator.EQ,
                NormalizedValue.Text("Ada"),
            ),
        )
        val fallback = planner.plan(
            PlanningFixtures.single(compatibleGap),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.COMPATIBLE, mandatory),
        ) as PlanningDecision.LegacyFallback
        fallback.validatedMandatory.condition.assert().isEqualTo(planned.filter.mandatory)
        fallback.validatedMandatory.target.assert().isEqualTo(PlanningFixtures.target)
        fallback.validatedMandatory.schemaContractId.assert().isEqualTo(PlanningFixtures.schema.contractId)
        fallback.issues.values.single().code.assert().isEqualTo(QueryRejectionCode.FIELD_NOT_FOUND)
    }

    @Test
    fun `mandatory failure should fail closed in compatible mode`() {
        val missingMandatory = predicate(
            PlanningFixtures.path("state", "missing"),
            PredicateOperator.EQ,
            NormalizedValue.Text("secret"),
        )

        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.FIELD_NOT_FOUND,
            "$.constraints.mandatoryCondition.field",
        ) {
            planner.plan(
                PlanningFixtures.single(),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.COMPATIBLE, missingMandatory),
            )
        }
    }

    @Test
    fun `typed projection should reject until legacy typed fallback is provably lossless`() {
        val include = NormalizedProjection.Include(NonEmptyList.of(PlanningFixtures.path("state", "name")))
        val mixed = NormalizedProjection.Mixed(
            NonEmptyList.of(PlanningFixtures.path("state", "name")),
            NonEmptyList.of(PlanningFixtures.path("state", "amount")),
        )

        QueryValidationMode.entries.forEach { validationMode ->
            listOf(include, mixed).forEach { projection ->
                listOf(
                    PlanningFixtures.single(PlanningFixtures.recordQuery(projection = projection)),
                    PlanningFixtures.stream(PlanningFixtures.recordQuery(projection = projection)),
                    PlanningFixtures.page(PlanningFixtures.recordQuery(projection = projection)),
                ).forEach { invocation ->
                    assertRejected(
                        QueryRejectionCategory.INVALID_QUERY,
                        QueryRejectionCode.TYPED_PROJECTION_NOT_ALLOWED,
                        "$.input.query.projection",
                    ) {
                        planner.plan(
                            invocation,
                            PlanningFixtures.schema,
                            constraints(validationMode),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `dynamic projection should canonicalize valid fields and preserve rejection path`() {
        val include = NormalizedProjection.Include(NonEmptyList.of(PlanningFixtures.path("state", "name")))
        val mixed = NormalizedProjection.Mixed(
            NonEmptyList.of(PlanningFixtures.path("state", "name")),
            NonEmptyList.of(PlanningFixtures.path("state", "amount")),
        )

        val dynamic = planner.plan(
            PlanningFixtures.single(
                PlanningFixtures.recordQuery(projection = include),
                QueryResultShape.DYNAMIC,
            ),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.STRICT),
        ).planned().plan as SingleQueryPlan
        dynamic.projection.assert().isEqualTo(PlannedProjection.Include(NonEmptyList.of(PlanningFixtures.name)))

        assertRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.INVALID_PROJECTION,
            "$.input.query.projection",
        ) {
            planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(projection = mixed), QueryResultShape.DYNAMIC),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.COMPATIBLE),
            )
        }

        val originalOrder = NormalizedProjection.Include(
            NonEmptyList.of(
                PlanningFixtures.path("state", "name"),
                PlanningFixtures.path("aggregateId"),
            ),
        )
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.CAPABILITY_UNAVAILABLE,
            "$.input.query.projection.fields[1]",
        ) {
            planner.plan(
                PlanningFixtures.single(
                    PlanningFixtures.recordQuery(projection = originalOrder),
                    QueryResultShape.DYNAMIC,
                ),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.STRICT),
            )
        }
    }

    @Test
    fun `planner should bind nested element fields to canonical logical ids`() {
        val condition = NormalizedCondition.ElementMatch(
            PlanningFixtures.path("state", "items"),
            predicate(
                PlanningFixtures.path("name", basis = PathBasis.CURRENT_ELEMENT),
                PredicateOperator.CONTAINS,
                NormalizedValue.Text("book"),
            ),
        )
        val plan = planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(condition)),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.STRICT),
        ).planned().plan as SingleQueryPlan
        val element = plan.filter.user as PlannedCondition.ElementMatch

        element.field.assert().isEqualTo(PlanningFixtures.items)
        (element.condition as PlannedCondition.Predicate).field.assert().isEqualTo(PlanningFixtures.itemName)
        plan.requiredCapabilities.fieldRequirements.getValue(PlanningFixtures.items).assert()
            .contains(FieldCapability.ELEMENT_MATCH)
        plan.requiredCapabilities.fieldRequirements.getValue(PlanningFixtures.itemName).assert()
            .contains(FieldCapability.LITERAL_PATTERN)
    }

    @Test
    fun `planner should recursively bind multi level element scopes`() {
        val condition = NormalizedCondition.ElementMatch(
            PlanningFixtures.path("state", "items"),
            NormalizedCondition.ElementMatch(
                PlanningFixtures.path("attributes", basis = PathBasis.CURRENT_ELEMENT),
                predicate(
                    PlanningFixtures.path("name", basis = PathBasis.CURRENT_ELEMENT),
                    PredicateOperator.EQ,
                    NormalizedValue.Text("color"),
                ),
            ),
        )
        val plan = planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(condition)),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.STRICT),
        ).planned().plan as SingleQueryPlan
        val outer = plan.filter.user as PlannedCondition.ElementMatch
        val inner = outer.condition as PlannedCondition.ElementMatch

        outer.field.assert().isEqualTo(PlanningFixtures.items)
        inner.field.assert().isEqualTo(PlanningFixtures.itemAttributes)
        (inner.condition as PlannedCondition.Predicate).field.assert().isEqualTo(PlanningFixtures.itemAttributeName)
    }

    @Test
    fun `element scope should reject root paths instead of escaping nested semantics`() {
        val condition = NormalizedCondition.ElementMatch(
            PlanningFixtures.path("state", "items"),
            predicate(
                PlanningFixtures.path("state", "name"),
                PredicateOperator.EQ,
                NormalizedValue.Text("outside"),
            ),
        )

        assertRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.INVALID_FIELD,
            "$.input.query.condition.condition.field",
        ) {
            planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(condition)),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.STRICT),
            )
        }
    }

    @Test
    fun `string exact literal and search should remain separate capabilities and tiers`() {
        val search = NormalizedCondition.Search(
            PlanningFixtures.legacySearch(PlanningFixtures.path("state", "description")),
            "distributed systems",
        )
        val plan = planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(search)),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.STRICT),
        ).planned().plan as SingleQueryPlan

        plan.semanticTier.assert().isEqualTo(SemanticTier.SEARCH)
        plan.requiredCapabilities.searchRequirements.assert().contains(SearchScopeId("order-description"))
        plan.requiredCapabilities.fieldRequirements.values.flatten().assert().doesNotContain(
            FieldCapability.EXACT,
            FieldCapability.LITERAL_PATTERN,
        )

        val insensitiveLiteral = predicate(
            PlanningFixtures.path("state", "name"),
            PredicateOperator.CONTAINS,
            NormalizedValue.Text("Ada"),
            NormalizedPredicateOptions(CaseSensitivity.INSENSITIVE),
        )
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.CASE_INSENSITIVE_UNSUPPORTED,
            "$.input.query.condition.options.caseSensitivity",
        ) {
            planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(insensitiveLiteral)),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.STRICT),
            )
        }
    }

    @Test
    fun `predicate operators should produce contextual logical capabilities`() {
        val predicates = listOf(
            predicate(
                PlanningFixtures.path("state", "name"),
                PredicateOperator.EQ,
                NormalizedValue.Text("Ada"),
            ) to (PlanningFixtures.name to FieldCapability.EXACT),
            predicate(
                PlanningFixtures.path("state", "amount"),
                PredicateOperator.GT,
                NormalizedValue.Int64(10),
            ) to (PlanningFixtures.amount to FieldCapability.RANGE),
            predicate(
                PlanningFixtures.path("state", "name"),
                PredicateOperator.EXISTS,
                NormalizedValue.BooleanValue(true),
            ) to (PlanningFixtures.name to FieldCapability.PRESENCE),
            predicate(
                PlanningFixtures.path("state", "name"),
                PredicateOperator.CONTAINS,
                NormalizedValue.Text("Ada"),
            ) to (PlanningFixtures.name to FieldCapability.LITERAL_PATTERN),
        )

        predicates.forEach { (condition, expected) ->
            val plan = planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(condition)),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.STRICT),
            ).planned().plan
            plan.requiredCapabilities.fieldRequirements.getValue(expected.first).assert().contains(expected.second)
        }
    }

    @Test
    fun `compatible schema gap should be explicit fallback and strict gap should reject`() {
        val physicalGuess = predicate(
            PlanningFixtures.path("state", "name", "keyword"),
            PredicateOperator.EQ,
            NormalizedValue.Text("Ada"),
        )
        val compatible = planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(physicalGuess)),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.COMPATIBLE),
        ) as PlanningDecision.LegacyFallback
        compatible.issues.values.single().path.toString().assert().isEqualTo("$.input.query.condition.field")
        compatible.issues.values.single().code.assert().isEqualTo(QueryRejectionCode.FIELD_NOT_FOUND)

        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.FIELD_NOT_FOUND,
            "$.input.query.condition.field",
        ) {
            planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(physicalGuess)),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.STRICT),
            )
        }
    }

    @Test
    fun `planner should reject inconsistent normalized invocation matrix`() {
        assertRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.INVALID_INVOCATION,
            "$.input",
        ) {
            planner.plan(
                NormalizedQueryInvocation(
                    PlanningFixtures.target,
                    QueryOperation.COUNT,
                    QueryResultShape.COUNT,
                    NormalizedQueryInput.Single(PlanningFixtures.recordQuery()),
                ),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.STRICT),
            )
        }
    }

    @Test
    fun `planner should validate normalized values against logical field types`() {
        val invalid = predicate(
            PlanningFixtures.path("state", "name"),
            PredicateOperator.EQ,
            NormalizedValue.Int64(1),
        )

        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.VALUE_TYPE_MISMATCH,
            "$.input.query.condition.value",
        ) {
            planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(invalid)),
                PlanningFixtures.schema,
                constraints(QueryValidationMode.STRICT),
            )
        }
        planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(invalid)),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.COMPATIBLE),
        ).assert().isInstanceOf(PlanningDecision.LegacyFallback::class.java)
    }

    @Test
    fun `planner should validate collection operands by operator and array element type`() {
        val tags = PlanningFixtures.path("state", "tags")
        val all = predicate(
            tags,
            PredicateOperator.ALL_IN,
            NormalizedValue.ListValue(listOf(NormalizedValue.Text("blue"), NormalizedValue.Null)),
        )
        planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(all)),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.STRICT),
        ).assert().isInstanceOf(PlanningDecision.Planned::class.java)

        val validBetween = predicate(
            PlanningFixtures.path("state", "amount"),
            PredicateOperator.BETWEEN,
            NormalizedValue.ListValue(listOf(NormalizedValue.Int64(1), NormalizedValue.Int64(2))),
        )
        planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(validBetween)),
            PlanningFixtures.schema,
            constraints(QueryValidationMode.STRICT),
        ).assert().isInstanceOf(PlanningDecision.Planned::class.java)

        listOf(1, 3).forEach { arity ->
            val between = predicate(
                PlanningFixtures.path("state", "amount"),
                PredicateOperator.BETWEEN,
                NormalizedValue.ListValue(List(arity) { NormalizedValue.Int64(it.toLong()) }),
            )
            assertRejected(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                QueryRejectionCode.VALUE_TYPE_MISMATCH,
                "$.input.query.condition.value",
            ) {
                planner.plan(
                    PlanningFixtures.single(PlanningFixtures.recordQuery(between)),
                    PlanningFixtures.schema,
                    constraints(QueryValidationMode.STRICT),
                )
            }
        }
    }

    @Test
    fun `range operators should reject null operands`() {
        listOf(
            predicate(
                PlanningFixtures.path("state", "amount"),
                PredicateOperator.GT,
                NormalizedValue.Null,
            ),
            predicate(
                PlanningFixtures.path("state", "amount"),
                PredicateOperator.BETWEEN,
                NormalizedValue.ListValue(listOf(NormalizedValue.Int64(1), NormalizedValue.Null)),
            ),
        ).forEach { invalidRange ->
            assertRejected(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                QueryRejectionCode.VALUE_TYPE_MISMATCH,
                "$.input.query.condition.value",
            ) {
                planner.plan(
                    PlanningFixtures.single(PlanningFixtures.recordQuery(invalidRange)),
                    PlanningFixtures.schema,
                    constraints(QueryValidationMode.STRICT),
                )
            }
        }
    }

    private fun constraints(
        validationMode: QueryValidationMode,
        mandatory: NormalizedCondition = NormalizedCondition.All,
    ): PlanningConstraints = PlanningConstraints(validationMode, mandatory)

    private fun predicate(
        field: LogicalField,
        operator: PredicateOperator,
        value: NormalizedValue,
        options: NormalizedPredicateOptions = NormalizedPredicateOptions(),
    ): NormalizedCondition.Predicate = NormalizedCondition.Predicate(field, operator, value, options)

    private fun PlanningDecision.planned(): PlanningDecision.Planned = this as PlanningDecision.Planned

    private fun assertRejected(
        category: QueryRejectionCategory,
        code: QueryRejectionCode,
        path: String,
        action: () -> Unit,
    ) {
        assertThrownBy<QueryRejectedException>(action).satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
                error.rejection.path.toString().assert().isEqualTo(path)
            },
        )
    }
}
