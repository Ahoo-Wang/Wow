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
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsDimension
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPromotion
import me.ahoo.wow.query.internal.analytics.AnalyticsOverflowPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedProjection
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SearchScope
import me.ahoo.wow.query.internal.normalization.SystemFieldKind
import me.ahoo.wow.query.internal.normalization.Utf8Json
import me.ahoo.wow.query.internal.plan.SingleQueryPlan
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test
import java.math.RoundingMode
import java.util.function.Consumer

class QueryPlannerAccessConstraintTest {
    private val planner = QueryPlanner()

    @Test
    fun `user filter should obey allow-list while mandatory may use hidden fields`() {
        val user = predicate(PlanningFixtures.path("state", "name"), NormalizedValue.Text("Ada"))
        val mandatory = predicate(
            LogicalField.System(SystemFieldKind.TENANT_ID),
            NormalizedValue.Text("tenant-1"),
        )
        val constraints = PlanningConstraints(
            validationMode = QueryValidationMode.STRICT,
            mandatoryCondition = mandatory,
            fieldConstraint = QueryFieldConstraint(filterFields = FieldAccess.AllowList(setOf(PlanningFixtures.name))),
        )

        val decision = planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(user)),
            PlanningFixtures.schema,
            constraints,
        ) as PlanningDecision.Planned
        val plan = decision.plan as SingleQueryPlan
        plan.filter.mandatory.assert().isNotEqualTo(plan.filter.user)

        assertRejected(QueryRejectionCode.FILTER_FIELD_NOT_ALLOWED, "$.input.query.condition.field") {
            planner.plan(
                PlanningFixtures.single(
                    PlanningFixtures.recordQuery(
                        predicate(PlanningFixtures.path("state", "amount"), NormalizedValue.Int64(1)),
                    ),
                ),
                PlanningFixtures.schema,
                constraints,
            )
        }
    }

    @Test
    fun `projection sort and search constraints should reject at original request path`() {
        val projection = NormalizedProjection.Include(NonEmptyList.of(PlanningFixtures.path("state", "amount")))
        assertRejected(QueryRejectionCode.PROJECTION_FIELD_NOT_ALLOWED, "$.input.query.projection.fields[0]") {
            planner.plan(
                PlanningFixtures.single(
                    PlanningFixtures.recordQuery(projection = projection),
                    QueryResultShape.DYNAMIC,
                ),
                PlanningFixtures.schema,
                PlanningConstraints(
                    QueryValidationMode.STRICT,
                    fieldConstraint = QueryFieldConstraint(projectionFields = FieldAccess.DenyAll),
                ),
            )
        }
        assertRejected(QueryRejectionCode.PROJECTION_FIELD_NOT_ALLOWED, "$.input.query.projection") {
            planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(projection = projection)),
                PlanningFixtures.schema,
                PlanningConstraints(
                    QueryValidationMode.COMPATIBLE,
                    fieldConstraint = QueryFieldConstraint(projectionFields = FieldAccess.DenyAll),
                ),
            )
        }

        assertRejected(QueryRejectionCode.SORT_FIELD_NOT_ALLOWED, "$.input.query.sort[0].field") {
            planner.plan(
                PlanningFixtures.page(
                    PlanningFixtures.recordQuery(
                        sort = listOf(PlanningFixtures.sort(PlanningFixtures.path("state", "amount"))),
                    ),
                ),
                PlanningFixtures.schema,
                PlanningConstraints(
                    QueryValidationMode.STRICT,
                    fieldConstraint = QueryFieldConstraint(sortFields = FieldAccess.DenyAll),
                ),
            )
        }

        val search = NormalizedCondition.Search(
            SearchScope.Named(PlanningFixtures.searchScopeId),
            "distributed systems",
        )
        assertRejected(QueryRejectionCode.SEARCH_SCOPE_NOT_ALLOWED, "$.input.query.condition.scope") {
            planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(search)),
                PlanningFixtures.schema,
                PlanningConstraints(
                    QueryValidationMode.STRICT,
                    fieldConstraint = QueryFieldConstraint(searchScopes = SearchScopeAccess.DenyAll),
                ),
            )
        }
    }

    @Test
    fun `restricted projection should reject full typed and exclude result shapes without fallback`() {
        listOf(QueryValidationMode.STRICT, QueryValidationMode.COMPATIBLE).forEach { mode ->
            assertRejected(QueryRejectionCode.PROJECTION_FIELD_NOT_ALLOWED, "$.input.query.projection") {
                planner.plan(
                    PlanningFixtures.single(),
                    PlanningFixtures.schema,
                    PlanningConstraints(
                        mode,
                        fieldConstraint = QueryFieldConstraint(projectionFields = FieldAccess.DenyAll),
                    ),
                )
            }
            assertRejected(QueryRejectionCode.PROJECTION_FIELD_NOT_ALLOWED, "$.input.query.projection") {
                planner.plan(
                    PlanningFixtures.single(
                        PlanningFixtures.recordQuery(
                            projection = NormalizedProjection.Exclude(
                                NonEmptyList.of(PlanningFixtures.path("state", "name")),
                            ),
                        ),
                        QueryResultShape.DYNAMIC,
                    ),
                    PlanningFixtures.schema,
                    PlanningConstraints(
                        mode,
                        fieldConstraint = QueryFieldConstraint(
                            projectionFields = FieldAccess.AllowList(setOf(PlanningFixtures.name)),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `result constraint should reject unbounded stream and oversized page`() {
        val maximum = ResultPlanningConstraint.MaximumRecords(10)
        val stream = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.STREAM,
            QueryResultShape.DYNAMIC,
            NormalizedQueryInput.Stream(PlanningFixtures.recordQuery(), limit = 0),
        )
        assertRejected(
            QueryRejectionCode.RESULT_LIMIT_EXCEEDED,
            "$.input.limit",
            QueryRejectionCategory.BUDGET_EXCEEDED,
        ) {
            planner.plan(
                stream,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT, resultConstraint = maximum),
            )
        }
        assertRejected(
            QueryRejectionCode.RESULT_LIMIT_EXCEEDED,
            "$.input.page",
            QueryRejectionCategory.BUDGET_EXCEEDED,
        ) {
            planner.plan(
                PlanningFixtures.page(size = 11),
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT, resultConstraint = maximum),
            )
        }

        listOf(
            stream.copy(input = NormalizedQueryInput.Stream(PlanningFixtures.recordQuery(), limit = 10)),
            PlanningFixtures.page(size = 10),
        ).forEach { invocation ->
            planner.plan(
                invocation,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT, resultConstraint = maximum),
            ).assert().isInstanceOf(PlanningDecision.Planned::class.java)
        }
    }

    @Test
    fun `analytics dimensions and metrics should use independent access dimensions`() {
        val dimensionQuery = AnalyticsQuery(
            NormalizedCondition.All,
            AnalyticsGrouping.By(
                NonEmptyList.of(
                    AnalyticsDimension(AnalyticsAlias("amount"), PlanningFixtures.path("state", "amount")),
                ),
            ),
            NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
        )
        assertRejected(
            QueryRejectionCode.ANALYTICS_DIMENSION_FIELD_NOT_ALLOWED,
            "$.input.query.grouping.dimensions[0].field",
        ) {
            planAnalytics(
                dimensionQuery,
                QueryFieldConstraint(analyticsDimensionFields = FieldAccess.DenyAll),
            )
        }

        val metricQuery = AnalyticsQuery(
            NormalizedCondition.All,
            AnalyticsGrouping.Global,
            NonEmptyList.of(
                AnalyticsMetric.Sum(AnalyticsAlias("total"), PlanningFixtures.path("state", "amount")),
            ),
            numericPolicy = AnalyticsNumericPolicy(
                AnalyticsNumericPromotion.DECIMAL128,
                precision = 34,
                scale = 8,
                roundingMode = RoundingMode.HALF_EVEN,
                overflowPolicy = AnalyticsOverflowPolicy.REJECT,
            ),
        )
        assertRejected(
            QueryRejectionCode.ANALYTICS_METRIC_FIELD_NOT_ALLOWED,
            "$.input.query.metrics[0].field",
        ) {
            planAnalytics(
                metricQuery,
                QueryFieldConstraint(analyticsMetricFields = FieldAccess.DenyAll),
            )
        }
    }

    @Test
    fun `unknown resources should not bypass restricted policy through compatible fallback`() {
        val missing = PlanningFixtures.path("state", "secret")
        val restricted = QueryFieldConstraint(
            filterFields = FieldAccess.AllowList(setOf(PlanningFixtures.name)),
            projectionFields = FieldAccess.AllowList(setOf(PlanningFixtures.name)),
            sortFields = FieldAccess.AllowList(setOf(PlanningFixtures.name)),
            searchScopes = SearchScopeAccess.AllowList(setOf(PlanningFixtures.searchScopeId)),
        )
        assertRejected(QueryRejectionCode.FILTER_FIELD_NOT_ALLOWED, "$.input.query.condition.field") {
            planner.plan(
                PlanningFixtures.single(
                    PlanningFixtures.recordQuery(predicate(missing, NormalizedValue.Text("secret"))),
                ),
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.COMPATIBLE, fieldConstraint = restricted),
            )
        }
        assertRejected(QueryRejectionCode.PROJECTION_FIELD_NOT_ALLOWED, "$.input.query.projection.fields[0]") {
            planner.plan(
                PlanningFixtures.single(
                    PlanningFixtures.recordQuery(projection = NormalizedProjection.Include(NonEmptyList.of(missing))),
                    QueryResultShape.DYNAMIC,
                ),
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.COMPATIBLE, fieldConstraint = restricted),
            )
        }
        assertRejected(QueryRejectionCode.SORT_FIELD_NOT_ALLOWED, "$.input.query.sort[0].field") {
            planner.plan(
                PlanningFixtures.page(
                    PlanningFixtures.recordQuery(
                        projection = NormalizedProjection.Include(
                            NonEmptyList.of(PlanningFixtures.path("state", "name")),
                        ),
                        sort = listOf(PlanningFixtures.sort(missing)),
                    ),
                    QueryResultShape.DYNAMIC,
                ),
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.COMPATIBLE, fieldConstraint = restricted),
            )
        }
        listOf(missing, PlanningFixtures.path("aggregateId")).forEach { legacyField ->
            assertRejected(QueryRejectionCode.SEARCH_SCOPE_NOT_ALLOWED, "$.input.query.condition.scope") {
                planner.plan(
                    PlanningFixtures.single(
                        PlanningFixtures.recordQuery(
                            NormalizedCondition.Search(SearchScope.LegacyField(legacyField), "secret"),
                        ),
                    ),
                    PlanningFixtures.schema,
                    PlanningConstraints(QueryValidationMode.COMPATIBLE, fieldConstraint = restricted),
                )
            }
        }
    }

    @Test
    fun `native backend access should reject before compatible fallback and permit only allowed backend`() {
        val native = NormalizedCondition.Native(BackendId("mongo"), Utf8Json("{}"))
        listOf(
            NativeBackendAccess.DenyAll,
            NativeBackendAccess.AllowList(setOf(BackendId("elasticsearch"))),
        ).forEach { access ->
            assertRejected(QueryRejectionCode.NATIVE_BACKEND_NOT_ALLOWED, "$.input.query.condition.backendId") {
                planner.plan(
                    PlanningFixtures.single(PlanningFixtures.recordQuery(native)),
                    PlanningFixtures.schema,
                    PlanningConstraints(
                        QueryValidationMode.COMPATIBLE,
                        fieldConstraint = QueryFieldConstraint(nativeBackends = access),
                    ),
                )
            }
        }

        val decision = planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(native)),
            PlanningFixtures.schema,
            PlanningConstraints(
                QueryValidationMode.COMPATIBLE,
                fieldConstraint = QueryFieldConstraint(
                    nativeBackends = NativeBackendAccess.AllowList(setOf(BackendId("mongo"))),
                ),
            ),
        ) as PlanningDecision.Planned
        decision.plan.requiredCapabilities.nativeBackend.assert().isEqualTo(BackendId("mongo"))

        val source = linkedSetOf(BackendId("mongo"))
        val access = NativeBackendAccess.AllowList(source)
        source += BackendId("elasticsearch")
        access.permits(BackendId("mongo")).assert().isTrue()
        access.permits(BackendId("elasticsearch")).assert().isFalse()
        access.assert().isEqualTo(NativeBackendAccess.AllowList(setOf(BackendId("mongo"))))
    }

    private fun planAnalytics(query: AnalyticsQuery, fieldConstraint: QueryFieldConstraint) {
        planner.plan(
            NormalizedQueryInvocation(
                PlanningFixtures.target,
                QueryOperation.ANALYZE,
                QueryResultShape.ANALYTICS,
                NormalizedQueryInput.Analytics(query),
            ),
            PlanningFixtures.schema,
            PlanningConstraints(QueryValidationMode.STRICT, fieldConstraint = fieldConstraint),
        )
    }

    private fun predicate(field: LogicalField, value: NormalizedValue): NormalizedCondition.Predicate =
        NormalizedCondition.Predicate(field, PredicateOperator.EQ, value)

    private fun assertRejected(
        code: QueryRejectionCode,
        path: String,
        category: QueryRejectionCategory = QueryRejectionCategory.ACCESS_DENIED,
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
