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

@file:OptIn(me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class)

package me.ahoo.wow.query.internal.execution

import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.BackendAnalyticsBucketOrder
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsCondition
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
import me.ahoo.wow.query.backend.BackendAnalyticsCursorState
import me.ahoo.wow.query.backend.BackendAnalyticsDimension
import me.ahoo.wow.query.backend.BackendAnalyticsGrouping
import me.ahoo.wow.query.backend.BackendAnalyticsMetric
import me.ahoo.wow.query.backend.BackendAnalyticsMissingPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsNullPlacement
import me.ahoo.wow.query.backend.BackendAnalyticsNumericPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsNumericPromotion
import me.ahoo.wow.query.backend.BackendAnalyticsOverflowPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsPageWindow
import me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan
import me.ahoo.wow.query.backend.BackendAnalyticsTextCollation
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.accepts
import me.ahoo.wow.query.internal.analytics.AnalyticsCompleteness
import me.ahoo.wow.query.internal.analytics.AnalyticsConsistency
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsBucketOrder
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsCondition
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsGrouping
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsMetric
import reactor.core.publisher.Mono
import me.ahoo.wow.query.backend.AnalyticsQueryBackend as ExperimentalAnalyticsQueryBackend
import me.ahoo.wow.query.backend.BackendAnalyticsPage as ExperimentalAnalyticsPage
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias as InternalAnalyticsAlias

internal class ExperimentalAnalyticsBackendAdapter(
    private val delegate: ExperimentalAnalyticsQueryBackend,
    private val schema: QueryDocumentSchema,
) : AnalyticsQueryBackend {
    override fun analyze(plan: AnalyticsQueryPlan, options: QueryExecutionOptions): Mono<BackendAnalyticsPage> =
        delegate.analyze(plan.toBackendPlan(), options.toBackendOptions()).map { page -> page.toInternal(plan, schema) }

    override fun analyze(
        plan: AnalyticsQueryPlan,
        options: QueryExecutionOptions,
        cursorState: ByteArray?,
    ): Mono<BackendAnalyticsPage> = delegate.analyze(
        plan.toBackendPlan(),
        options.toBackendOptions(),
        cursorState?.let(::BackendAnalyticsCursorState),
    ).map { page -> page.toInternal(plan, schema) }
}

private fun AnalyticsQueryPlan.toBackendPlan(): BackendAnalyticsQueryPlan = BackendAnalyticsQueryPlan(
    target,
    schemaContractId,
    filter.toBackendFilter(),
    grouping.toBackendGrouping(),
    metrics.values.map(PlannedAnalyticsMetric::toBackendMetric),
    when (having) {
        PlannedAnalyticsCondition.All -> BackendAnalyticsCondition.All
    },
    bucketOrder.toBackendOrder(),
    BackendAnalyticsPageWindow(bucketWindow.limit, bucketWindow.afterKey?.values),
    numericPolicy?.let { policy ->
        BackendAnalyticsNumericPolicy(
            BackendAnalyticsNumericPromotion.valueOf(policy.promotion.name),
            policy.precision,
            policy.scale,
            policy.roundingMode,
            BackendAnalyticsOverflowPolicy.valueOf(policy.overflowPolicy.name),
        )
    },
    BackendAnalyticsConsistency.valueOf(requiredConsistency.name),
    BackendAnalyticsCompleteness.valueOf(requiredCompleteness.name),
    requiredCapabilities.toBackendCapabilities(),
    semanticTier.toBackendTier(),
    PlanFingerprint(fingerprint.value),
)

private fun PlannedAnalyticsGrouping.toBackendGrouping(): BackendAnalyticsGrouping =
    when (this) {
        PlannedAnalyticsGrouping.Global -> BackendAnalyticsGrouping.Global
        is PlannedAnalyticsGrouping.By -> BackendAnalyticsGrouping.By(
            dimensions.values.map { dimension ->
                BackendAnalyticsDimension(
                    dimension.alias.toBackendAlias(),
                    dimension.field,
                    BackendAnalyticsMissingPolicy.valueOf(dimension.missingPolicy.name),
                )
            },
        )
    }

private fun PlannedAnalyticsMetric.toBackendMetric(): BackendAnalyticsMetric =
    when (this) {
        is PlannedAnalyticsMetric.DocumentCount -> BackendAnalyticsMetric.DocumentCount(alias.toBackendAlias())
        is PlannedAnalyticsMetric.Min -> BackendAnalyticsMetric.Min(alias.toBackendAlias(), field)
        is PlannedAnalyticsMetric.Max -> BackendAnalyticsMetric.Max(alias.toBackendAlias(), field)
        is PlannedAnalyticsMetric.Sum -> BackendAnalyticsMetric.Sum(alias.toBackendAlias(), field)
        is PlannedAnalyticsMetric.Average -> BackendAnalyticsMetric.Average(alias.toBackendAlias(), field)
    }

private fun PlannedAnalyticsBucketOrder.toBackendOrder(): BackendAnalyticsBucketOrder =
    when (this) {
        PlannedAnalyticsBucketOrder.Global -> BackendAnalyticsBucketOrder.Global
        is PlannedAnalyticsBucketOrder.DimensionKeyAscending -> BackendAnalyticsBucketOrder.DimensionKeyAscending(
            BackendAnalyticsNullPlacement.valueOf(nullPlacement.name),
            BackendAnalyticsTextCollation.valueOf(textCollation.name),
        )
    }

private fun ExperimentalAnalyticsPage.toInternal(
    plan: AnalyticsQueryPlan,
    schema: QueryDocumentSchema,
): BackendAnalyticsPage {
    validateResult(plan, schema)
    return BackendAnalyticsPage(
        buckets.map { bucket ->
            BackendAnalyticsBucket(
                bucket.keys.mapKeys { entry -> entry.key.toInternalAlias() },
                bucket.metrics.mapKeys { entry -> entry.key.toInternalAlias() },
            )
        },
        afterKey,
        when (consistency) {
            BackendAnalyticsConsistency.EVENTUAL -> AnalyticsConsistency.EVENTUAL
            BackendAnalyticsConsistency.SNAPSHOT -> AnalyticsConsistency.SNAPSHOT
        },
        when (completeness) {
            BackendAnalyticsCompleteness.EXACT -> AnalyticsCompleteness.EXACT
            BackendAnalyticsCompleteness.APPROXIMATE -> AnalyticsCompleteness.APPROXIMATE
        },
        cursorState?.payload(),
    )
}

private fun ExperimentalAnalyticsPage.validateResult(plan: AnalyticsQueryPlan, schema: QueryDocumentSchema) {
    val dimensions = when (val grouping = plan.grouping) {
        PlannedAnalyticsGrouping.Global -> emptyList()
        is PlannedAnalyticsGrouping.By -> grouping.dimensions.values
    }
    val dimensionsByAlias = dimensions.associateBy { dimension -> dimension.alias.value }
    val metricsByAlias = plan.metrics.values.associateBy { metric -> metric.alias.value }
    buckets.forEach { bucket ->
        validateBucket(schema, bucket, dimensionsByAlias, metricsByAlias)
    }
    afterKey?.forEachIndexed { index, value ->
        requireDimensionValue(schema, dimensions.getOrNull(index) ?: mappingFailure(), value)
    }
}

private fun validateBucket(
    schema: QueryDocumentSchema,
    bucket: me.ahoo.wow.query.backend.BackendAnalyticsBucket,
    dimensionsByAlias: Map<String, me.ahoo.wow.query.internal.plan.PlannedAnalyticsDimension>,
    metricsByAlias: Map<String, PlannedAnalyticsMetric>,
) {
    bucket.keys.forEach { (alias, value) ->
        requireDimensionValue(schema, dimensionsByAlias[alias.value] ?: mappingFailure(), value)
    }
    bucket.metrics.forEach { (alias, value) ->
        requireMetricValue(schema, metricsByAlias[alias.value] ?: mappingFailure(), value)
    }
}

private fun requireMetricValue(
    schema: QueryDocumentSchema,
    metric: PlannedAnalyticsMetric,
    value: NormalizedValue,
) {
    when (metric) {
        is PlannedAnalyticsMetric.DocumentCount ->
            if (value !is NormalizedValue.Int64 || value.value < 0) mappingFailure()

        is PlannedAnalyticsMetric.Min -> requireFieldValue(schema, metric.field, value, allowNull = true)
        is PlannedAnalyticsMetric.Max -> requireFieldValue(schema, metric.field, value, allowNull = true)
        is PlannedAnalyticsMetric.Sum,
        is PlannedAnalyticsMetric.Average,
        -> requireNumericMetricValue(value)
    }
}

private fun requireNumericMetricValue(value: NormalizedValue) {
    if (value != NormalizedValue.Null &&
        value !is NormalizedValue.Int64 &&
        value !is NormalizedValue.Decimal
    ) {
        mappingFailure()
    }
}

private fun requireDimensionValue(
    schema: QueryDocumentSchema,
    dimension: me.ahoo.wow.query.internal.plan.PlannedAnalyticsDimension,
    value: NormalizedValue,
) {
    if (value == NormalizedValue.Null) {
        if (dimension.missingPolicy.name != "AS_NULL_BUCKET") mappingFailure()
        return
    }
    requireFieldValue(schema, dimension.field, value, allowNull = false)
}

private fun requireFieldValue(
    schema: QueryDocumentSchema,
    fieldId: me.ahoo.wow.query.backend.QueryFieldId,
    value: NormalizedValue,
    allowNull: Boolean,
) {
    if (allowNull && value == NormalizedValue.Null) return
    val field = schema.fields[fieldId] ?: mappingFailure()
    if (!field.type.accepts(value)) mappingFailure()
}

private fun mappingFailure(): Nothing = throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE)

private fun InternalAnalyticsAlias.toBackendAlias(): AnalyticsAlias = AnalyticsAlias(value)

private fun AnalyticsAlias.toInternalAlias(): InternalAnalyticsAlias = InternalAnalyticsAlias(value)
