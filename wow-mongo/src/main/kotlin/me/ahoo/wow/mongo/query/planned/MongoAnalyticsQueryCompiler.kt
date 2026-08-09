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

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import me.ahoo.wow.query.backend.BackendAnalyticsBucketOrder
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsCondition
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
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
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.SemanticTier
import org.bson.Document
import org.bson.conversions.Bson

internal data class MongoCompiledAnalyticsQuery(
    val pipeline: List<Bson>,
    val dimensions: List<BackendAnalyticsDimension>,
    val metrics: List<BackendAnalyticsMetric>,
    val resultLimit: Int,
)

internal class MongoAnalyticsQueryCompiler(
    private val binding: MongoPreparedQueryBinding,
) {
    private val recordCompiler = MongoRecordQueryCompiler(binding)

    fun compile(plan: BackendAnalyticsQueryPlan): MongoCompiledAnalyticsQuery {
        validatePlan(plan)
        val dimensions = (plan.grouping as? BackendAnalyticsGrouping.By)?.dimensions.orEmpty()
        val resultLimit = try {
            Math.addExact(plan.bucketWindow.limit, 1)
        } catch (error: ArithmeticException) {
            throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED, error)
        }
        val pipeline = buildList {
            add(Aggregates.match(recordCompiler.compileFilter(plan.filter)))
            compileMissingFilter(dimensions)?.let { missing -> add(Aggregates.match(missing)) }
            add(compileGroup(plan.grouping, plan.metrics))
            compileCursor(dimensions, plan.bucketWindow.afterKey)?.let { cursor -> add(Aggregates.match(cursor)) }
            compileSort(plan.bucketOrder, dimensions)?.let { sort -> add(Aggregates.sort(sort)) }
            add(Aggregates.limit(resultLimit))
        }
        return MongoCompiledAnalyticsQuery(pipeline, dimensions, plan.metrics, resultLimit)
    }

    private fun validatePlan(plan: BackendAnalyticsQueryPlan) {
        validateContract(plan)
        validateGrouping(plan)
        validateMetrics(plan)
    }

    private fun validateContract(plan: BackendAnalyticsQueryPlan) {
        if (plan.target != binding.schema.target || plan.schemaContractId != binding.schema.contractId) {
            unsupported()
        }
        if (plan.having != BackendAnalyticsCondition.All || plan.semanticTier != SemanticTier.PORTABLE) {
            unsupported()
        }
        if (plan.requiredConsistency != BackendAnalyticsConsistency.EVENTUAL ||
            plan.requiredCompleteness != BackendAnalyticsCompleteness.EXACT
        ) {
            unsupported()
        }
    }

    private fun validateGrouping(plan: BackendAnalyticsQueryPlan) {
        when (val grouping = plan.grouping) {
            BackendAnalyticsGrouping.Global -> validateGlobalGrouping(plan)

            is BackendAnalyticsGrouping.By -> validateGroupedGrouping(plan, grouping)
        }
    }

    private fun validateGlobalGrouping(plan: BackendAnalyticsQueryPlan) {
        if (plan.bucketOrder != BackendAnalyticsBucketOrder.Global ||
            plan.bucketWindow.afterKey != null ||
            plan.bucketWindow.limit != 1
        ) {
            unsupported()
        }
    }

    private fun validateGroupedGrouping(
        plan: BackendAnalyticsQueryPlan,
        grouping: BackendAnalyticsGrouping.By,
    ) {
        val order = plan.bucketOrder as? BackendAnalyticsBucketOrder.DimensionKeyAscending ?: unsupported()
        if (order.nullPlacement != BackendAnalyticsNullPlacement.FIRST ||
            order.textCollation != BackendAnalyticsTextCollation.BINARY ||
            plan.bucketWindow.afterKey?.size?.let { size -> size != grouping.dimensions.size } == true
        ) {
            unsupported()
        }
        grouping.dimensions.forEach { dimension -> requireAggregatable(dimension.field) }
        plan.bucketWindow.afterKey?.forEachIndexed { index, value ->
            if (!acceptsCursorValue(grouping.dimensions[index], value)) {
                unsupported()
            }
        }
    }

    private fun validateMetrics(plan: BackendAnalyticsQueryPlan) {
        var needsNumericPolicy = false
        plan.metrics.forEach { metric ->
            when (metric) {
                is BackendAnalyticsMetric.DocumentCount -> Unit
                is BackendAnalyticsMetric.Min -> {
                    needsNumericPolicy = validateMetricField(metric.field, allowInstant = true) || needsNumericPolicy
                }

                is BackendAnalyticsMetric.Max -> {
                    needsNumericPolicy = validateMetricField(metric.field, allowInstant = true) || needsNumericPolicy
                }

                is BackendAnalyticsMetric.Sum -> {
                    validateMetricField(metric.field, allowInstant = false)
                    needsNumericPolicy = true
                }

                is BackendAnalyticsMetric.Average -> {
                    validateMetricField(metric.field, allowInstant = false)
                    needsNumericPolicy = true
                }
            }
        }
        if ((plan.numericPolicy != null) != needsNumericPolicy) {
            unsupported()
        }
    }

    private fun validateMetricField(field: QueryFieldId, allowInstant: Boolean): Boolean {
        requireAggregatable(field)
        return when (binding.schema.fields.getValue(field).type) {
            LogicalFieldType.Int64,
            LogicalFieldType.Decimal,
            -> true

            LogicalFieldType.Instant -> if (allowInstant) false else unsupported()
            else -> unsupported()
        }
    }

    private fun requireAggregatable(field: QueryFieldId): MongoFieldBinding =
        recordCompiler.requireFieldBinding(field).also { fieldBinding ->
            if (FieldCapability.AGGREGATABLE !in fieldBinding.capabilities ||
                field is QueryFieldId.Path && binding.schema.elementOwner(field) != null
            ) {
                unsupported()
            }
        }

    private fun acceptsCursorValue(
        dimension: BackendAnalyticsDimension,
        value: NormalizedValue,
    ): Boolean {
        val schemaField = binding.schema.fields.getValue(dimension.field)
        if (value == NormalizedValue.Null) {
            return dimension.missingPolicy == BackendAnalyticsMissingPolicy.AS_NULL_BUCKET &&
                (schemaField.presence == Presence.OPTIONAL || schemaField.nullability == Nullability.NULLABLE)
        }
        return when (schemaField.type) {
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

    private fun compileMissingFilter(dimensions: List<BackendAnalyticsDimension>): Bson? {
        val filters = dimensions.filter { dimension ->
            dimension.missingPolicy == BackendAnalyticsMissingPolicy.EXCLUDE
        }.map { dimension ->
            val path = requireAggregatable(dimension.field).path
            Filters.and(Filters.exists(path, true), Filters.ne(path, null))
        }
        return filters.takeIf(List<*>::isNotEmpty)?.let(Filters::and)
    }

    private fun compileGroup(
        grouping: BackendAnalyticsGrouping,
        metrics: List<BackendAnalyticsMetric>,
    ): Bson {
        val id = when (grouping) {
            BackendAnalyticsGrouping.Global -> null
            is BackendAnalyticsGrouping.By -> Document().also { document ->
                grouping.dimensions.forEach { dimension ->
                    val field = requireAggregatable(dimension.field)
                    document[dimension.alias.value] = when (dimension.missingPolicy) {
                        BackendAnalyticsMissingPolicy.EXCLUDE -> "\$${field.path}"
                        BackendAnalyticsMissingPolicy.AS_NULL_BUCKET -> Document(
                            "\$ifNull",
                            listOf("\$${field.path}", null),
                        )
                    }
                }
            }
        }
        val group = Document("_id", id)
        metrics.forEach { metric -> group[metric.alias.value] = compileMetric(metric) }
        return Document("\$group", group)
    }

    private fun compileMetric(metric: BackendAnalyticsMetric): Any =
        when (metric) {
            is BackendAnalyticsMetric.DocumentCount -> Document("\$sum", 1)
            is BackendAnalyticsMetric.Min -> Document("\$min", fieldExpression(metric.field))
            is BackendAnalyticsMetric.Max -> Document("\$max", fieldExpression(metric.field))
            is BackendAnalyticsMetric.Sum -> Document("\$sum", decimalExpression(metric.field))
            is BackendAnalyticsMetric.Average -> Document("\$avg", decimalExpression(metric.field))
        }

    private fun fieldExpression(field: QueryFieldId): String =
        "\$${requireAggregatable(field).path}"

    private fun decimalExpression(field: QueryFieldId): Document {
        val fieldBinding = requireAggregatable(field)
        if (fieldBinding.valueEncoding != MongoValueEncoding.DECIMAL128) {
            unsupported()
        }
        return Document("\$toDecimal", "\$${fieldBinding.path}")
    }

    private fun compileCursor(
        dimensions: List<BackendAnalyticsDimension>,
        afterKey: List<NormalizedValue>?,
    ): Bson? {
        if (afterKey == null) {
            return null
        }
        if (dimensions.size != afterKey.size) {
            unsupported()
        }
        val encoded = dimensions.mapIndexed { index, dimension ->
            recordCompiler.encodeFieldValue(dimension.field, afterKey[index])
        }
        val branches = dimensions.indices.map { index ->
            Document().also { branch ->
                repeat(index) { prefix -> branch[dimensionKey(dimensions[prefix])] = encoded[prefix] }
                branch[dimensionKey(dimensions[index])] = Document("\$gt", encoded[index])
            }
        }
        return Document("\$or", branches)
    }

    private fun compileSort(
        order: BackendAnalyticsBucketOrder,
        dimensions: List<BackendAnalyticsDimension>,
    ): Bson? = when (order) {
        BackendAnalyticsBucketOrder.Global -> null
        is BackendAnalyticsBucketOrder.DimensionKeyAscending -> Document().also { sort ->
            dimensions.forEach { dimension -> sort[dimensionKey(dimension)] = 1 }
        }
    }

    private fun dimensionKey(dimension: BackendAnalyticsDimension): String = "_id.${dimension.alias.value}"

    private fun unsupported(): Nothing = throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)
}
