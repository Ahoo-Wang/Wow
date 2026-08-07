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

import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsBucketOrder
import me.ahoo.wow.query.internal.analytics.AnalyticsBucketWindow
import me.ahoo.wow.query.internal.analytics.AnalyticsCompleteness
import me.ahoo.wow.query.internal.analytics.AnalyticsCondition
import me.ahoo.wow.query.internal.analytics.AnalyticsConsistency
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.analytics.AnalyticsMissingPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNullPlacement
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPromotion
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.analytics.AnalyticsTextCollation
import me.ahoo.wow.query.internal.analytics.DecodedAnalyticsCursor
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.plan.AnalyticsPageWindow
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
import me.ahoo.wow.query.internal.plan.EnforcedFilter
import me.ahoo.wow.query.internal.plan.PlanFingerprint
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsBucketOrder
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsCondition
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsDimension
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsGrouping
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsMetric
import me.ahoo.wow.query.internal.plan.RequiredCapabilities
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.schema.FieldCapability
import me.ahoo.wow.query.internal.schema.LogicalFieldType
import me.ahoo.wow.query.internal.schema.Nullability
import me.ahoo.wow.query.internal.schema.Presence
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema
import me.ahoo.wow.query.internal.schema.QueryFieldId
import me.ahoo.wow.query.internal.value.NonEmptyList

internal class AnalyticsQueryPlanner(
    private val invocation: NormalizedQueryInvocation,
    private val schema: QueryDocumentSchema,
    private val conditionPlanner: QueryConditionPlanner,
    private val constraints: PlanningConstraints,
    private val mandatory: ValidatedMandatory,
) {
    private val queryPath = QueryRejectionPath.ROOT.property("input").property("query")

    fun plan(query: AnalyticsQuery): AnalyticsQueryPlan {
        validateContract(query)
        validateAliases(query)
        val user = conditionPlanner.plan(
            query.userCondition,
            queryPath.property("userCondition"),
            mandatory = false,
            fieldConstraint = constraints.fieldConstraint,
        )
        if (user.semanticTier != SemanticTier.PORTABLE) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                queryPath.property("userCondition"),
                QueryRejectionCode.CAPABILITY_UNAVAILABLE,
            )
        }
        if (mandatory.semanticTier != SemanticTier.PORTABLE) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                QueryRejectionPath.ROOT.property("constraints").property("mandatoryCondition"),
                QueryRejectionCode.CAPABILITY_UNAVAILABLE,
            )
        }
        val requirements = linkedMapOf<QueryFieldId, MutableSet<FieldCapability>>()
        val grouping = planGrouping(query.grouping, requirements)
        val metrics = planMetrics(query, requirements)
        val capabilities = mergeCapabilities(
            user.requiredCapabilities,
            mandatory.requiredCapabilities,
            RequiredCapabilities(requirements.mapValues { it.value.toSet() }),
        )
        val afterKey = (query.bucketWindow as? AnalyticsBucketWindow.After)?.cursor?.afterKey
        val plan = AnalyticsQueryPlan.create(
            invocation.target,
            schema.contractId,
            EnforcedFilter(user.condition, mandatory.condition),
            grouping,
            metrics.values,
            PlannedAnalyticsCondition.All,
            query.grouping.plannedOrder(),
            AnalyticsPageWindow(query.effectiveBucketLimit(), afterKey),
            metrics.numericPolicy,
            query.requiredConsistency,
            query.requiredCompleteness,
            capabilities,
            SemanticTier.PORTABLE,
        )
        (query.bucketWindow as? AnalyticsBucketWindow.After)?.cursor?.let { cursor ->
            validateCursor(cursor, grouping, plan.fingerprint)
        }
        return plan
    }

    private fun validateContract(query: AnalyticsQuery) {
        validateBudget(query)
        if (invocation.target.documentKind != QueryDocumentKind.SNAPSHOT) {
            rejectUnsupported(
                "target.documentKind",
                QueryRejectionCode.ANALYTICS_DOCUMENT_KIND_UNSUPPORTED,
                root = true,
            )
        }
        if (query.having != AnalyticsCondition.All) {
            rejectUnsupported("having", QueryRejectionCode.ANALYTICS_HAVING_UNSUPPORTED)
        }
        if (!query.hasPortableOrder()) {
            rejectUnsupported("bucketOrder", QueryRejectionCode.ANALYTICS_ORDER_UNSUPPORTED)
        }
        if (query.requiredConsistency != AnalyticsConsistency.EVENTUAL) {
            rejectUnsupported("requiredConsistency", QueryRejectionCode.ANALYTICS_CONSISTENCY_UNSUPPORTED)
        }
        if (query.requiredCompleteness != AnalyticsCompleteness.EXACT) {
            rejectUnsupported("requiredCompleteness", QueryRejectionCode.ANALYTICS_COMPLETENESS_UNSUPPORTED)
        }
        if (query.grouping == AnalyticsGrouping.Global && query.bucketWindow is AnalyticsBucketWindow.After) {
            rejectInvalidCursor(queryPath.property("bucketWindow").property("cursor"))
        }
    }

    private fun validateBudget(query: AnalyticsQuery) {
        val limits = constraints.analyticsConstraint as? AnalyticsPlanningConstraint.Limits ?: return
        val dimensions = (query.grouping as? AnalyticsGrouping.By)?.dimensions?.values?.size ?: 0
        if (dimensions > limits.maxDimensions) {
            rejectBudget("grouping.dimensions", QueryRejectionCode.ANALYTICS_DIMENSION_LIMIT_EXCEEDED)
        }
        if (query.metrics.values.size > limits.maxMetrics) {
            rejectBudget("metrics", QueryRejectionCode.ANALYTICS_METRIC_LIMIT_EXCEEDED)
        }
        if (query.effectiveBucketLimit() > limits.maxBucketLimit) {
            rejectBudget("bucketWindow.limit", QueryRejectionCode.ANALYTICS_BUCKET_LIMIT_EXCEEDED)
        }
    }

    private fun validateAliases(query: AnalyticsQuery) {
        val seen = mutableSetOf<AnalyticsAlias>()
        val dimensions = (query.grouping as? AnalyticsGrouping.By)?.dimensions?.values.orEmpty()
        dimensions.forEachIndexed { index, dimension ->
            if (!seen.add(dimension.alias)) {
                rejectDuplicateAlias(queryPath.property("grouping").property("dimensions").index(index))
            }
        }
        query.metrics.values.forEachIndexed { index, metric ->
            if (!seen.add(metric.alias)) {
                rejectDuplicateAlias(queryPath.property("metrics").index(index))
            }
        }
    }

    private fun planGrouping(
        grouping: AnalyticsGrouping,
        requirements: MutableMap<QueryFieldId, MutableSet<FieldCapability>>,
    ): PlannedAnalyticsGrouping =
        when (grouping) {
            AnalyticsGrouping.Global -> PlannedAnalyticsGrouping.Global
            is AnalyticsGrouping.By -> PlannedAnalyticsGrouping.By(
                checkNotNull(
                    NonEmptyList.from(
                        grouping.dimensions.values.mapIndexed { index, dimension ->
                            val path = queryPath.property("grouping").property("dimensions").index(index)
                            val field = conditionPlanner.resolveAccessibleField(
                                dimension.field,
                                path.property("field"),
                                constraints.fieldConstraint.analyticsDimensionFields,
                                QueryRejectionCode.ANALYTICS_DIMENSION_FIELD_NOT_ALLOWED,
                            )
                            if (!schema.fields.getValue(field).type.isPortableDimension()) {
                                rejectQuery(
                                    QueryRejectionCategory.UNSUPPORTED_FEATURE,
                                    path.property("field"),
                                    QueryRejectionCode.ANALYTICS_DIMENSION_TYPE_UNSUPPORTED,
                                )
                            }
                            if (field is QueryFieldId.Path && schema.elementOwner(field) != null) {
                                rejectQuery(
                                    QueryRejectionCategory.UNSUPPORTED_FEATURE,
                                    path.property("field"),
                                    QueryRejectionCode.ANALYTICS_DIMENSION_TYPE_UNSUPPORTED,
                                )
                            }
                            requireFieldCapability(schema, field, FieldCapability.AGGREGATABLE, path.property("field"))
                            requirements.require(field, FieldCapability.AGGREGATABLE)
                            PlannedAnalyticsDimension(dimension.alias, field, dimension.missingPolicy)
                        },
                    ),
                ),
            )
        }

    private fun planMetrics(
        query: AnalyticsQuery,
        requirements: MutableMap<QueryFieldId, MutableSet<FieldCapability>>,
    ): PlannedMetrics {
        var needsNumericPolicy = false
        val metrics = query.metrics.values.mapIndexed { index, metric ->
            val path = queryPath.property("metrics").index(index)
            when (metric) {
                is AnalyticsMetric.DocumentCount -> PlannedAnalyticsMetric.DocumentCount(metric.alias)
                is AnalyticsMetric.Min -> {
                    val field = planMetricField(metric.field, path, allowInstant = true)
                    needsNumericPolicy = needsNumericPolicy || schema.fields.getValue(field).type.isNumeric()
                    requirements.require(field, FieldCapability.AGGREGATABLE)
                    PlannedAnalyticsMetric.Min(metric.alias, field)
                }

                is AnalyticsMetric.Max -> {
                    val field = planMetricField(metric.field, path, allowInstant = true)
                    needsNumericPolicy = needsNumericPolicy || schema.fields.getValue(field).type.isNumeric()
                    requirements.require(field, FieldCapability.AGGREGATABLE)
                    PlannedAnalyticsMetric.Max(metric.alias, field)
                }

                is AnalyticsMetric.Sum -> {
                    val field = planMetricField(metric.field, path, allowInstant = false)
                    needsNumericPolicy = true
                    requirements.require(field, FieldCapability.AGGREGATABLE)
                    PlannedAnalyticsMetric.Sum(metric.alias, field)
                }

                is AnalyticsMetric.Average -> {
                    val field = planMetricField(metric.field, path, allowInstant = false)
                    needsNumericPolicy = true
                    requirements.require(field, FieldCapability.AGGREGATABLE)
                    PlannedAnalyticsMetric.Average(metric.alias, field)
                }
            }
        }
        if (needsNumericPolicy && query.numericPolicy == null) {
            rejectUnsupported("numericPolicy", QueryRejectionCode.ANALYTICS_NUMERIC_POLICY_REQUIRED)
        }
        val numericPolicy = query.numericPolicy.takeIf { needsNumericPolicy }
        numericPolicy?.let { policy ->
            if (policy.promotion != AnalyticsNumericPromotion.DECIMAL128 ||
                policy.precision > DECIMAL128_MAX_PRECISION
            ) {
                rejectUnsupported("numericPolicy", QueryRejectionCode.ANALYTICS_NUMERIC_POLICY_UNSUPPORTED)
            }
        }
        return PlannedMetrics(checkNotNull(NonEmptyList.from(metrics)), numericPolicy)
    }

    private fun planMetricField(
        logicalField: LogicalField,
        path: QueryRejectionPath,
        allowInstant: Boolean,
    ): QueryFieldId {
        val field = conditionPlanner.resolveAccessibleField(
            logicalField,
            path.property("field"),
            constraints.fieldConstraint.analyticsMetricFields,
            QueryRejectionCode.ANALYTICS_METRIC_FIELD_NOT_ALLOWED,
        )
        val type = schema.fields.getValue(field).type
        if (field is QueryFieldId.Path && schema.elementOwner(field) != null) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                path.property("field"),
                QueryRejectionCode.ANALYTICS_METRIC_TYPE_UNSUPPORTED,
            )
        }
        if (!type.isNumeric() && !(allowInstant && type == LogicalFieldType.Instant)) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                path.property("field"),
                QueryRejectionCode.ANALYTICS_METRIC_TYPE_UNSUPPORTED,
            )
        }
        requireFieldCapability(schema, field, FieldCapability.AGGREGATABLE, path.property("field"))
        return field
    }

    private fun validateCursor(
        cursor: DecodedAnalyticsCursor,
        grouping: PlannedAnalyticsGrouping,
        fingerprint: PlanFingerprint,
    ) {
        val path = queryPath.property("bucketWindow").property("cursor")
        if (cursor.target != invocation.target) {
            rejectInvalidCursor(path.property("target"))
        }
        if (cursor.planFingerprint != fingerprint) {
            rejectInvalidCursor(path.property("planFingerprint"))
        }
        val dimensions = (grouping as? PlannedAnalyticsGrouping.By)?.dimensions?.values
            ?: rejectInvalidCursor(path.property("dimensionAliases"))
        if (cursor.dimensionAliases.values != dimensions.map { it.alias }) {
            rejectInvalidCursor(path.property("dimensionAliases"))
        }
        if (cursor.afterKey.values.size != dimensions.size) {
            rejectInvalidCursor(path.property("afterKey"))
        }
        cursor.afterKey.values.forEachIndexed { index, value ->
            val dimension = dimensions[index]
            if (!acceptsCursorKey(dimension, value)) {
                rejectInvalidCursor(path.property("afterKey").index(index))
            }
        }
    }

    private fun acceptsCursorKey(
        dimension: PlannedAnalyticsDimension,
        value: NormalizedValue,
    ): Boolean {
        val fieldSchema = schema.fields.getValue(dimension.field)
        if (value == NormalizedValue.Null) {
            return dimension.missingPolicy == AnalyticsMissingPolicy.AS_NULL_BUCKET &&
                (fieldSchema.presence == Presence.OPTIONAL || fieldSchema.nullability == Nullability.NULLABLE)
        }
        return when (fieldSchema.type) {
            LogicalFieldType.Text -> value is NormalizedValue.Text
            LogicalFieldType.Boolean -> value is NormalizedValue.BooleanValue
            LogicalFieldType.Int64 -> value is NormalizedValue.Int64
            LogicalFieldType.Decimal -> value is NormalizedValue.Decimal
            LogicalFieldType.Instant -> value is NormalizedValue.InstantValue
            LogicalFieldType.Bytes,
            LogicalFieldType.Object,
            is LogicalFieldType.Array,
            -> false
        }
    }

    private fun rejectUnsupported(
        property: String,
        code: QueryRejectionCode,
        root: Boolean = false,
    ): Nothing {
        val path = if (root) {
            property.split('.').fold(QueryRejectionPath.ROOT) { current, segment -> current.property(segment) }
        } else {
            queryPath.property(property)
        }
        rejectQuery(QueryRejectionCategory.UNSUPPORTED_FEATURE, path, code)
    }

    private fun rejectDuplicateAlias(path: QueryRejectionPath): Nothing = rejectQuery(
        QueryRejectionCategory.INVALID_QUERY,
        path.property("alias"),
        QueryRejectionCode.DUPLICATE_ANALYTICS_ALIAS,
    )

    private fun rejectBudget(property: String, code: QueryRejectionCode): Nothing {
        val path = property.split('.').fold(queryPath) { current, segment -> current.property(segment) }
        rejectQuery(QueryRejectionCategory.BUDGET_EXCEEDED, path, code)
    }

    private fun rejectInvalidCursor(path: QueryRejectionPath): Nothing = rejectQuery(
        QueryRejectionCategory.INVALID_CURSOR,
        path,
        QueryRejectionCode.INVALID_CURSOR_BINDING,
    )

    private data class PlannedMetrics(
        val values: NonEmptyList<PlannedAnalyticsMetric>,
        val numericPolicy: AnalyticsNumericPolicy?,
    )
}

private fun MutableMap<QueryFieldId, MutableSet<FieldCapability>>.require(
    field: QueryFieldId,
    capability: FieldCapability,
) {
    getOrPut(field, ::linkedSetOf) += capability
}

private fun LogicalFieldType.isNumeric(): Boolean = this == LogicalFieldType.Int64 || this == LogicalFieldType.Decimal

private fun LogicalFieldType.isPortableDimension(): Boolean =
    this == LogicalFieldType.Text ||
        this == LogicalFieldType.Boolean ||
        this == LogicalFieldType.Int64 ||
        this == LogicalFieldType.Decimal ||
        this == LogicalFieldType.Instant

private fun AnalyticsQuery.hasPortableOrder(): Boolean =
    when (grouping) {
        AnalyticsGrouping.Global -> bucketOrder == AnalyticsBucketOrder.Default
        is AnalyticsGrouping.By -> {
            bucketOrder == AnalyticsBucketOrder.Default || bucketOrder == AnalyticsBucketOrder.DimensionKeyAscending
        }
    }

private fun AnalyticsGrouping.plannedOrder(): PlannedAnalyticsBucketOrder =
    when (this) {
        AnalyticsGrouping.Global -> PlannedAnalyticsBucketOrder.Global
        is AnalyticsGrouping.By -> PlannedAnalyticsBucketOrder.DimensionKeyAscending(
            AnalyticsNullPlacement.FIRST,
            AnalyticsTextCollation.BINARY,
        )
    }

private fun AnalyticsQuery.effectiveBucketLimit(): Int =
    if (grouping == AnalyticsGrouping.Global) 1 else bucketWindow.limit

private const val DECIMAL128_MAX_PRECISION = 34
