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

package me.ahoo.wow.query.compat

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryPageSpec
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.expression.LegacyConditionLowering

@JvmSynthetic
internal fun legacyDynamicShape(projection: Projection): QueryResultShape<DynamicDocument> =
    QueryResultShape.Typed(DynamicDocument::class.java, projection.toCanonical())

@JvmSynthetic
internal fun legacyTypedDynamicShape(projection: Projection): QueryResultShape<DynamicDocument> =
    QueryResultShape.Typed(legacyTypedDynamicDocumentType(), projection.toCanonical())

@JvmSynthetic
internal fun legacyTypedSingleRequest(
    target: QueryTarget,
    query: ISingleQuery
): SingleQueryRequest<DynamicDocument> = legacySingleRequest(target, query, legacyTypedDynamicShape(query.projection))

@JvmSynthetic
internal fun legacyTypedListRequest(
    target: QueryTarget,
    query: IListQuery
): ListQueryRequest<DynamicDocument> = legacyListRequest(target, query, legacyTypedDynamicShape(query.projection))

@JvmSynthetic
internal fun legacyTypedPageRequest(
    target: QueryTarget,
    query: IPagedQuery
): PageQueryRequest<DynamicDocument> = legacyPageRequest(target, query, legacyTypedDynamicShape(query.projection))

@JvmSynthetic
internal fun legacySingleRequest(
    target: QueryTarget,
    query: ISingleQuery
): SingleQueryRequest<DynamicDocument> = legacySingleRequest(target, query, legacyDynamicShape(query.projection))

private fun legacySingleRequest(
    target: QueryTarget,
    query: ISingleQuery,
    resultShape: QueryResultShape<DynamicDocument>
): SingleQueryRequest<DynamicDocument> = query.condition.lower(target) { lowered, scope ->
    SingleQueryRequest(
        target,
        lowered.first,
        resultShape,
        scope,
        sort = query.sort.toCanonical()
    )
}

@JvmSynthetic
internal fun legacyListRequest(
    target: QueryTarget,
    query: IListQuery
): ListQueryRequest<DynamicDocument> = legacyListRequest(target, query, legacyDynamicShape(query.projection))

private fun legacyListRequest(
    target: QueryTarget,
    query: IListQuery,
    resultShape: QueryResultShape<DynamicDocument>
): ListQueryRequest<DynamicDocument> = query.condition.lower(target) { lowered, scope ->
    ListQueryRequest(
        target,
        lowered.first,
        resultShape,
        scope,
        sort = query.sort.toCanonical(),
        limit = query.limit
    )
}

@JvmSynthetic
internal fun legacyPageRequest(
    target: QueryTarget,
    query: IPagedQuery
): PageQueryRequest<DynamicDocument> = legacyPageRequest(target, query, legacyDynamicShape(query.projection))

private fun legacyPageRequest(
    target: QueryTarget,
    query: IPagedQuery,
    resultShape: QueryResultShape<DynamicDocument>
): PageQueryRequest<DynamicDocument> = query.condition.lower(target) { lowered, scope ->
    PageQueryRequest(
        target,
        lowered.first,
        resultShape,
        scope,
        sort = query.sort.toCanonical(),
        page = QueryPageSpec(query.pagination.index, query.pagination.size)
    )
}

@JvmSynthetic
internal fun legacyCountRequest(target: QueryTarget, condition: Condition): CountQueryRequest =
    condition.lower(target) { lowered, scope ->
        CountQueryRequest(target, lowered.first, scope)
    }

@JvmSynthetic
@Suppress("UNCHECKED_CAST")
internal fun <R : QueryRequest> R.withExpression(expression: QueryExpression): R = when (this) {
    is SingleQueryRequest<*> -> copy(expression = expression)
    is ListQueryRequest<*> -> copy(expression = expression)
    is PageQueryRequest<*> -> copy(expression = expression)
    is CountQueryRequest -> copy(expression = expression)
} as R

private fun <T> Condition.lower(
    target: QueryTarget,
    transform: (Pair<QueryExpression, DeletionScope>, RequestedQueryScope) -> T
): T = try {
    val lowered = LegacyConditionLowering.lowerForGateway(this, target)
    transform(lowered, legacyRequestedScope(lowered.second))
} catch (error: QueryException) {
    throw error
} catch (_: RuntimeException) {
    invalidLegacyRequest()
}

@JvmSynthetic
internal fun Condition.legacyRequestedScope(deletion: DeletionScope): RequestedQueryScope {
    val candidates = when (operator) {
        Operator.TENANT_ID,
        Operator.OWNER_ID,
        Operator.SPACE_ID -> listOf(this)
        Operator.AND -> children.filter(Condition::isScopeCondition)
        else -> emptyList()
    }
    return RequestedQueryScope(
        tenantId = candidates.uniqueScopeValue(Operator.TENANT_ID),
        ownerId = candidates.uniqueScopeValue(Operator.OWNER_ID),
        spaceId = candidates.uniqueScopeValue(Operator.SPACE_ID),
        deletion = deletion,
    )
}

private fun Condition.isScopeCondition(): Boolean = when (operator) {
    Operator.TENANT_ID,
    Operator.OWNER_ID,
    Operator.SPACE_ID -> true
    else -> false
}

private fun List<Condition>.uniqueScopeValue(operator: Operator): String? {
    val values = asSequence().filter { it.operator == operator }.map { condition ->
        (condition.value as? String)?.takeIf(String::isNotBlank) ?: invalidLegacyRequest()
    }.toCollection(LinkedHashSet())
    if (values.size > 1) {
        invalidLegacyRequest()
    }
    return values.singleOrNull()
}

private fun List<Sort>.toCanonical(): List<QuerySort> = map { sort ->
    QuerySort(
        LogicalField(sort.field),
        sort.direction.toCanonical()
    )
}

private fun Sort.Direction.toCanonical(): QuerySortDirection =
    if (this == Sort.Direction.ASC) {
        QuerySortDirection.ASC
    } else if (this == Sort.Direction.DESC) {
        QuerySortDirection.DESC
    } else {
        invalidLegacyRequest()
    }

private fun Projection.toCanonical(): QueryProjection = try {
    when {
        include.isNotEmpty() && exclude.isNotEmpty() -> invalidLegacyRequest()
        include.isNotEmpty() -> QueryProjection.Include(include.mapTo(LinkedHashSet(), ::LogicalField))
        exclude.isNotEmpty() -> QueryProjection.Exclude(exclude.mapTo(LinkedHashSet(), ::LogicalField))
        else -> QueryProjection.All
    }
} catch (error: QueryException) {
    throw error
} catch (_: RuntimeException) {
    invalidLegacyRequest()
}

private fun invalidLegacyRequest(): Nothing = throw QueryException(
    QueryErrorCode.INVALID_QUERY,
    QueryStage.NORMALIZE,
    QueryErrorReason.INVALID_REQUEST
)

@Suppress("UNCHECKED_CAST")
private fun legacyTypedDynamicDocumentType(): Class<DynamicDocument> =
    LegacyTypedDynamicDocumentMarker::class.java as Class<DynamicDocument>

@JvmSynthetic
internal fun Class<*>.isLegacyTypedDynamicDocumentMarker(): Boolean =
    this == LegacyTypedDynamicDocumentMarker::class.java

private abstract class LegacyTypedDynamicDocumentMarker : DynamicDocument
