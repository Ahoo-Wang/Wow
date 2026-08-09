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

package me.ahoo.wow.query.internal.plan

import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryFieldId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal object QueryPlanFingerprint {
    const val VERSION: Int = 1

    fun compute(plan: QueryPlan): PlanFingerprint {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeUtf8(plan.target.namedAggregate.contextName)
            output.writeUtf8(plan.target.namedAggregate.aggregateName)
            output.writeUtf8(plan.target.documentKind.name)
            output.writeUtf8(plan.operation.name)
            output.writeUtf8(plan.schemaContractId.value)
            output.writeCondition(plan.filter.user)
            output.writeCondition(plan.filter.mandatory)
            output.writeUtf8(plan.semanticTier.name)
            output.writeCapabilities(plan.requiredCapabilities)
            when (plan) {
                is SingleQueryPlan -> output.writeRecord(plan)
                is StreamQueryPlan -> {
                    output.writeRecord(plan)
                    when (val limit = plan.limit) {
                        StreamLimit.Unbounded -> output.writeByte(0)
                        is StreamLimit.Bounded -> {
                            output.writeByte(1)
                            output.writeInt(limit.value)
                        }
                    }
                }

                is PageQueryPlan -> {
                    output.writeRecord(plan)
                    output.writeLong(plan.page.offset)
                    output.writeInt(plan.page.size)
                    output.writeUtf8(plan.totalMode.name)
                    output.writeUtf8(plan.requiredConsistency.name)
                }

                is CountQueryPlan -> Unit
                is AnalyticsQueryPlan -> output.writeAnalytics(plan)
            }
        }
        return PlanFingerprint(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toHex())
    }

    private fun DataOutputStream.writeRecord(plan: RecordQueryPlan) {
        writeUtf8(plan.resultShape.name)
        writeProjection(plan.projection)
        writeInt(plan.sort.size)
        plan.sort.forEach { sort ->
            writeField(sort.field)
            writeUtf8(sort.direction.name)
            writeUtf8(sort.origin.name)
        }
    }

    private fun DataOutputStream.writeAnalytics(plan: AnalyticsQueryPlan) {
        when (val grouping = plan.grouping) {
            PlannedAnalyticsGrouping.Global -> writeByte(0)
            is PlannedAnalyticsGrouping.By -> {
                writeByte(1)
                writeInt(grouping.dimensions.values.size)
                grouping.dimensions.values.forEach { dimension ->
                    writeUtf8(dimension.alias.value)
                    writeField(dimension.field)
                    writeUtf8(dimension.missingPolicy.name)
                }
            }
        }
        writeInt(plan.metrics.values.size)
        plan.metrics.values.forEach { metric ->
            when (metric) {
                is PlannedAnalyticsMetric.DocumentCount -> writeByte(0)
                is PlannedAnalyticsMetric.Min -> {
                    writeByte(1)
                    writeField(metric.field)
                }

                is PlannedAnalyticsMetric.Max -> {
                    writeByte(2)
                    writeField(metric.field)
                }

                is PlannedAnalyticsMetric.Sum -> {
                    writeByte(3)
                    writeField(metric.field)
                }

                is PlannedAnalyticsMetric.Average -> {
                    writeByte(4)
                    writeField(metric.field)
                }
            }
            writeUtf8(metric.alias.value)
        }
        when (plan.having) {
            PlannedAnalyticsCondition.All -> writeByte(0)
        }
        when (val order = plan.bucketOrder) {
            PlannedAnalyticsBucketOrder.Global -> writeByte(0)
            is PlannedAnalyticsBucketOrder.DimensionKeyAscending -> {
                writeByte(1)
                writeUtf8(order.nullPlacement.name)
                writeUtf8(order.textCollation.name)
            }
        }
        writeInt(plan.bucketWindow.limit)
        writeBoolean(plan.numericPolicy != null)
        plan.numericPolicy?.let { policy ->
            writeUtf8(policy.promotion.name)
            writeInt(policy.precision)
            writeInt(policy.scale)
            writeUtf8(policy.roundingMode.name)
            writeUtf8(policy.overflowPolicy.name)
        }
        writeUtf8(plan.requiredConsistency.name)
        writeUtf8(plan.requiredCompleteness.name)
    }

    private fun DataOutputStream.writeProjection(projection: PlannedProjection) {
        when (projection) {
            PlannedProjection.All -> writeByte(0)
            is PlannedProjection.Include -> {
                writeByte(1)
                writeFields(projection.fields.values)
            }

            is PlannedProjection.Exclude -> {
                writeByte(2)
                writeFields(projection.fields.values)
            }
        }
    }

    private fun DataOutputStream.writeCondition(condition: PlannedCondition) {
        when (condition) {
            PlannedCondition.All -> writeByte(0)
            PlannedCondition.None -> writeByte(1)
            is PlannedCondition.Junction -> {
                writeByte(2)
                writeUtf8(condition.operator.name)
                writeInt(condition.children.values.size)
                condition.children.values.forEach { child -> writeCondition(child) }
            }

            is PlannedCondition.Predicate -> {
                writeByte(3)
                writeField(condition.field)
                writeUtf8(condition.operator.name)
                writeBoolean(condition.value != null)
                condition.value?.let { value -> writeValue(value) }
                writeUtf8(condition.options.caseSensitivity.name)
            }

            is PlannedCondition.ElementMatch -> {
                writeByte(4)
                writeField(condition.field)
                writeCondition(condition.condition)
            }

            is PlannedCondition.Search -> {
                writeByte(5)
                writeUtf8(condition.scope.value)
                writeUtf8(condition.text)
            }

            is PlannedCondition.Native -> {
                writeByte(6)
                writeUtf8(condition.backendId.value)
                writeUtf8(condition.payload.value)
            }
        }
    }

    private fun DataOutputStream.writeValue(value: NormalizedValue) {
        when (value) {
            NormalizedValue.Null -> writeByte(0)
            is NormalizedValue.BooleanValue -> {
                writeByte(1)
                writeBoolean(value.value)
            }

            is NormalizedValue.Text -> {
                writeByte(2)
                writeUtf8(value.value)
            }

            is NormalizedValue.Int64 -> {
                writeByte(3)
                writeLong(value.value)
            }

            is NormalizedValue.Decimal -> {
                writeByte(4)
                writeByteArray(value.value.unscaledValue().toByteArray())
                writeInt(value.value.scale())
            }

            is NormalizedValue.InstantValue -> {
                writeByte(5)
                writeLong(value.value.epochSecond)
                writeInt(value.value.nano)
            }

            is NormalizedValue.Bytes -> {
                writeByte(6)
                writeByteArray(value.toByteArray())
            }

            is NormalizedValue.ListValue -> {
                writeByte(7)
                writeInt(value.values.size)
                value.values.forEach { item -> writeValue(item) }
            }

            is NormalizedValue.ObjectValue -> {
                writeByte(8)
                writeInt(value.values.size)
                value.values.forEach { (key, item) ->
                    writeUtf8(key)
                    writeValue(item)
                }
            }
        }
    }

    private fun DataOutputStream.writeCapabilities(capabilities: RequiredCapabilities) {
        writeInt(capabilities.fieldRequirements.size)
        capabilities.fieldRequirements.forEach { (field, requirements) ->
            writeField(field)
            writeStrings(requirements.map { it.name }.sorted())
        }
        writeStrings(capabilities.searchRequirements.map { it.value }.sorted())
        writeBoolean(capabilities.nativeBackend != null)
        capabilities.nativeBackend?.let { writeUtf8(it.value) }
    }

    private fun DataOutputStream.writeFields(fields: List<QueryFieldId>) {
        writeInt(fields.size)
        fields.forEach { field -> writeField(field) }
    }

    private fun DataOutputStream.writeField(field: QueryFieldId) {
        when (field) {
            is QueryFieldId.System -> {
                writeByte(0)
                writeUtf8(field.kind.name)
            }

            is QueryFieldId.Path -> {
                writeByte(1)
                writeStrings(field.segments)
            }
        }
    }

    private fun DataOutputStream.writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach { value -> writeUtf8(value) }
    }

    private fun DataOutputStream.writeByteArray(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataOutputStream.writeUtf8(value: String) = writeByteArray(value.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
