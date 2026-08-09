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

package me.ahoo.wow.query.internal.admission

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.analytics.AnalyticsGroupingKind
import me.ahoo.wow.api.query.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery

internal class AnalyticsAdmissionGuard(
    private val limits: QueryAdmissionLimits,
) {
    fun admit(
        query: AnalyticsQuery,
        path: QueryRejectionPath,
        budget: AdmissionBudget,
        conditionAdmission: (Condition, QueryRejectionPath) -> AdmittedCondition,
    ): AdmittedAnalyticsQuery {
        val dimensions = query.grouping.dimensions
        val metrics = query.metrics
        if (dimensions.size > limits.maxCollectionSize || metrics.size > limits.maxCollectionSize) {
            rejectBudget(path, QueryRejectionCode.COLLECTION_LIMIT_EXCEEDED)
        }
        dimensions.forEachIndexed { index, dimension ->
            val dimensionPath = path.property("grouping").property("dimensions").index(index)
            budget.consumeString(dimension.alias, dimensionPath.property("alias"))
            validateField(dimension.field, dimensionPath.property("field"))
            budget.consumeUtf8(dimension.field, dimensionPath.property("field"))
        }
        metrics.forEachIndexed { index, metric ->
            val metricPath = path.property("metrics").index(index)
            budget.consumeString(metric.alias, metricPath.property("alias"))
            metric.field?.let { field ->
                validateField(field, metricPath.property("field"))
                budget.consumeUtf8(field, metricPath.property("field"))
            }
        }
        query.window.cursor?.let { cursor ->
            budget.consumeString(cursor.value, path.property("window").property("cursor"))
        }
        if (query.grouping.kind == AnalyticsGroupingKind.GLOBAL && query.window.cursor != null) {
            rejectInvalid(path.property("window").property("cursor"), QueryRejectionCode.INVALID_CURSOR_BINDING)
        }
        return AdmittedAnalyticsQuery(
            condition = conditionAdmission(query.condition, path.property("condition")),
            grouping = query.grouping,
            metrics = metrics,
            window = query.window,
            numericPolicy = query.numericPolicy,
            consistency = query.consistency,
            completeness = query.completeness,
        )
    }

    private fun validateField(field: String, path: QueryRejectionPath) {
        if (field.isBlank()) {
            rejectInvalid(path, QueryRejectionCode.FIELD_REQUIRED)
        }
        if (field.length > limits.maxFieldLength) {
            rejectBudget(path, QueryRejectionCode.STRING_LIMIT_EXCEEDED)
        }
        if (field.split('.').any { it.isBlank() || it.any(Char::isISOControl) }) {
            rejectInvalid(path, QueryRejectionCode.INVALID_FIELD)
        }
    }

    private fun rejectInvalid(path: QueryRejectionPath, code: QueryRejectionCode): Nothing =
        rejectQuery(QueryRejectionCategory.INVALID_QUERY, path, code)

    private fun rejectBudget(path: QueryRejectionPath, code: QueryRejectionCode): Nothing =
        rejectQuery(QueryRejectionCategory.BUDGET_EXCEEDED, path, code)
}
