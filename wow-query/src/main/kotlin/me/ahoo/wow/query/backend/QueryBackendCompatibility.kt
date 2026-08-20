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

@file:JvmSynthetic

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import java.util.ArrayDeque

@JvmSynthetic
internal fun validateBackendCompatibility(
    context: QueryBackendResolutionContext,
    descriptor: QueryBackendDescriptor,
) {
    inspectBackendCompatibility(context.target.documentKind, context.securedExpression, descriptor)
}

@JvmSynthetic
internal fun inspectBackendCompatibility(
    documentKind: QueryDocumentKind,
    expression: QueryExpression,
    descriptor: QueryBackendDescriptor,
): Triple<Set<LogicalField>, Set<QueryCapabilityId>, Long> {
    if (documentKind !in descriptor.documentKinds || QueryPlanVersion.V1 !in descriptor.planVersions) {
        throw backendNotReady()
    }
    val inspection = inspect(expression)
    val supportsPortableExpression =
        descriptor.portableOperators.containsAll(inspection.portableOperators) &&
            descriptor.portableFeatures.containsAll(inspection.portableFeatures) &&
            descriptor.stringComparisonModes.containsAll(inspection.stringComparisonModes)
    val supportsCapabilities = descriptor.capabilities.containsAll(inspection.capabilities) &&
        inspection.nativeBackendIds.all { it == descriptor.backendId }
    if (!supportsPortableExpression || !supportsCapabilities) {
        throw unsupportedCapability()
    }
    return Triple(inspection.fields, inspection.capabilities, inspection.nodeCount)
}

private fun inspect(expression: QueryExpression): BackendCompatibilityInspection {
    val operators = LinkedHashSet<PortableOperator>()
    val features = LinkedHashSet<QueryPortableFeature>()
    val stringComparisonModes = LinkedHashSet<StringComparisonMode>()
    val capabilities = LinkedHashSet<QueryCapabilityId>()
    val nativeBackendIds = LinkedHashSet<String>()
    val fields = LinkedHashSet<LogicalField>()
    val pending = ArrayDeque<BackendExpressionFrame>()
    pending += BackendExpressionFrame(expression, null)
    var nodeCount = 0L
    while (pending.isNotEmpty()) {
        val frame = pending.removeLast()
        nodeCount++
        when (val current = frame.expression) {
            MatchAll,
            MatchNone -> Unit

            is LogicalExpression -> current.operands.forEach {
                pending += BackendExpressionFrame(it, frame.relativeTo)
            }
            is PortableLogicalExpression -> current.operands.forEach {
                pending += BackendExpressionFrame(it, frame.relativeTo)
            }
            is PredicateExpression -> {
                operators += current.operator
                if (current.operator in STRING_COMPARISON_OPERATORS) {
                    stringComparisonModes += current.stringComparison
                }
                fields += resolvePath(frame.relativeTo, current.field)
            }

            is ElementMatchExpression -> {
                features += QueryPortableFeature.ELEMENT_MATCH
                val field = resolvePath(frame.relativeTo, current.field)
                fields += field
                pending += BackendExpressionFrame(current.predicate, field)
            }

            is FullTextExpression -> {
                capabilities += current.capabilityId
                fields += current.fields.map { resolvePath(frame.relativeTo, it) }
            }

            is NativeExpression -> {
                capabilities += current.capabilityId
                nativeBackendIds += current.backendId
                fields += current.declaredFields.map { resolvePath(frame.relativeTo, it) }
            }
            is RelativeTimeExpression -> throw invalidQuery()
        }
    }
    return BackendCompatibilityInspection(
        operators,
        features,
        stringComparisonModes,
        capabilities,
        nativeBackendIds,
        fields,
        nodeCount,
    )
}

private fun resolvePath(relativeTo: LogicalField?, field: LogicalField): LogicalField =
    if (relativeTo == null) field else LogicalField("${relativeTo.value}.${field.value}")

private fun backendNotReady(): QueryException = QueryException(
    QueryErrorCode.BACKEND_NOT_READY,
    QueryStage.PLANNING,
    QueryErrorReason.BACKEND_UNAVAILABLE,
)

private fun unsupportedCapability(): QueryException = QueryException(
    QueryErrorCode.UNSUPPORTED_CAPABILITY,
    QueryStage.PLANNING,
    QueryErrorReason.CAPABILITY_DENIED,
)

private fun invalidQuery(): QueryException = QueryException(
    QueryErrorCode.INVALID_QUERY,
    QueryStage.PLANNING,
    QueryErrorReason.INVALID_REQUEST,
)

private class BackendCompatibilityInspection(
    val portableOperators: Set<PortableOperator>,
    val portableFeatures: Set<QueryPortableFeature>,
    val stringComparisonModes: Set<StringComparisonMode>,
    val capabilities: Set<QueryCapabilityId>,
    val nativeBackendIds: Set<String>,
    val fields: Set<LogicalField>,
    val nodeCount: Long,
)

private class BackendExpressionFrame(
    val expression: QueryExpression,
    val relativeTo: LogicalField?,
)

private val STRING_COMPARISON_OPERATORS = setOf(
    PortableOperator.CONTAINS,
    PortableOperator.STARTS_WITH,
    PortableOperator.ENDS_WITH,
)
