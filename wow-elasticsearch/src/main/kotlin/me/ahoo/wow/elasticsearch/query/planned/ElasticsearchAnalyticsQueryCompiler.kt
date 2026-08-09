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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.aggregations.MissingOrder
import co.elastic.clients.util.NamedValue
import me.ahoo.wow.query.backend.BackendAnalyticsBucketOrder
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsCondition
import me.ahoo.wow.query.backend.BackendAnalyticsDimension
import me.ahoo.wow.query.backend.BackendAnalyticsGrouping
import me.ahoo.wow.query.backend.BackendAnalyticsMetric
import me.ahoo.wow.query.backend.BackendAnalyticsMissingPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsNullPlacement
import me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan
import me.ahoo.wow.query.backend.BackendAnalyticsTextCollation
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.SemanticTier

internal data class ElasticsearchCompiledAnalyticsQuery(
    val query: co.elastic.clients.elasticsearch._types.query_dsl.Query,
    val grouping: BackendAnalyticsGrouping,
    val metrics: List<BackendAnalyticsMetric>,
    val aggregation: Aggregation?,
)

internal class ElasticsearchAnalyticsQueryCompiler(
    private val binding: ElasticsearchPreparedQueryBinding,
) {
    private val recordCompiler = ElasticsearchRecordQueryCompiler(binding)

    fun compile(plan: BackendAnalyticsQueryPlan): ElasticsearchCompiledAnalyticsQuery {
        validateContract(plan)
        validateMetrics(plan)
        val aggregation = when (val grouping = plan.grouping) {
            BackendAnalyticsGrouping.Global -> {
                validateGlobal(plan)
                null
            }

            is BackendAnalyticsGrouping.By -> compileComposite(plan, grouping)
        }
        return ElasticsearchCompiledAnalyticsQuery(
            recordCompiler.compileCondition(plan.filter.condition),
            plan.grouping,
            plan.metrics,
            aggregation,
        )
    }

    private fun validateContract(plan: BackendAnalyticsQueryPlan) {
        if (plan.target != binding.schema.target || plan.schemaContractId != binding.schema.contractId) unsupported()
        if (plan.having != BackendAnalyticsCondition.All || plan.semanticTier != SemanticTier.PORTABLE) unsupported()
        if (plan.requiredCompleteness != BackendAnalyticsCompleteness.EXACT) {
            unsupported()
        }
    }

    private fun validateMetrics(plan: BackendAnalyticsQueryPlan) {
        if (plan.metrics.any { metric -> metric !is BackendAnalyticsMetric.DocumentCount }) unsupported()
        if (plan.numericPolicy != null) unsupported()
    }

    private fun validateGlobal(plan: BackendAnalyticsQueryPlan) {
        if (plan.bucketOrder != BackendAnalyticsBucketOrder.Global ||
            plan.bucketWindow.limit != 1 ||
            plan.bucketWindow.afterKey != null
        ) {
            unsupported()
        }
    }

    private fun compileComposite(
        plan: BackendAnalyticsQueryPlan,
        grouping: BackendAnalyticsGrouping.By,
    ): Aggregation {
        val order = plan.bucketOrder as? BackendAnalyticsBucketOrder.DimensionKeyAscending ?: unsupported()
        if (order.nullPlacement != BackendAnalyticsNullPlacement.FIRST ||
            order.textCollation != BackendAnalyticsTextCollation.BINARY
        ) {
            unsupported()
        }
        val sources = grouping.dimensions.map(::compileSource)
        val after = plan.bucketWindow.afterKey?.let { values ->
            if (values.size != grouping.dimensions.size) unsupported()
            LinkedHashMap<String, FieldValue>(values.size).also { result ->
                grouping.dimensions.forEachIndexed { index, dimension ->
                    result[dimension.alias.value] = encodeDimensionValue(dimension, values[index])
                }
            }
        }
        return Aggregation.of { aggregation ->
            aggregation.composite { composite ->
                composite.size(plan.bucketWindow.limit)
                    .sources(sources)
                    .also { builder -> after?.let(builder::after) }
            }
        }
    }

    private fun compileSource(dimension: BackendAnalyticsDimension): NamedValue<CompositeAggregationSource> {
        val field = requireGroupField(dimension.field)
        return NamedValue.of(
            dimension.alias.value,
            CompositeAggregationSource.of { source ->
                source.terms { terms ->
                    val missingBucket = dimension.missingPolicy == BackendAnalyticsMissingPolicy.AS_NULL_BUCKET
                    terms.field(field.groupField)
                        .order(SortOrder.Asc)
                        .missingBucket(missingBucket)
                        .also { builder ->
                            if (missingBucket) {
                                builder.missingOrder(MissingOrder.First)
                            }
                        }
                }
            },
        )
    }

    private fun encodeDimensionValue(
        dimension: BackendAnalyticsDimension,
        value: NormalizedValue,
    ): FieldValue {
        if (value == NormalizedValue.Null) {
            if (dimension.missingPolicy == BackendAnalyticsMissingPolicy.AS_NULL_BUCKET) return FieldValue.NULL
            unsupported()
        }
        return when (binding.schema.fields.getValue(dimension.field).type) {
            LogicalFieldType.Text -> FieldValue.of((value as? NormalizedValue.Text)?.value ?: unsupported())
            LogicalFieldType.Boolean -> FieldValue.of(
                (value as? NormalizedValue.BooleanValue)?.value ?: unsupported(),
            )

            LogicalFieldType.Int64 -> FieldValue.of((value as? NormalizedValue.Int64)?.value ?: unsupported())
            LogicalFieldType.Instant -> {
                if (requireGroupField(dimension.field).valueEncoding != ElasticsearchValueEncoding.EPOCH_MILLIS) {
                    unsupported()
                }
                FieldValue.of((value as? NormalizedValue.InstantValue)?.value?.toEpochMilli() ?: unsupported())
            }

            LogicalFieldType.Decimal,
            LogicalFieldType.Bytes,
            LogicalFieldType.Object,
            is LogicalFieldType.Array,
            -> unsupported()
        }
    }

    private fun requireGroupField(field: QueryFieldId): ElasticsearchFieldBinding {
        if (field is QueryFieldId.Path && binding.schema.elementOwner(field) != null) unsupported()
        val fieldBinding = binding.fields[field] ?: unsupported()
        if (FieldCapability.AGGREGATABLE !in fieldBinding.capabilities || fieldBinding.groupField == null) unsupported()
        return fieldBinding
    }

    private fun unsupported(): Nothing = throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)
}
