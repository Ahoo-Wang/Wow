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
internal fun legacySingleRequest(
    target: QueryTarget,
    query: ISingleQuery
): SingleQueryRequest<DynamicDocument> = query.condition.lower(target) { lowered ->
    SingleQueryRequest(
        target,
        lowered.first,
        legacyDynamicShape(query.projection),
        lowered.scope(),
        sort = query.sort.toCanonical()
    )
}

@JvmSynthetic
internal fun legacyListRequest(
    target: QueryTarget,
    query: IListQuery
): ListQueryRequest<DynamicDocument> = query.condition.lower(target) { lowered ->
    ListQueryRequest(
        target,
        lowered.first,
        legacyDynamicShape(query.projection),
        lowered.scope(),
        sort = query.sort.toCanonical(),
        limit = query.limit
    )
}

@JvmSynthetic
internal fun legacyPageRequest(
    target: QueryTarget,
    query: IPagedQuery
): PageQueryRequest<DynamicDocument> = query.condition.lower(target) { lowered ->
    PageQueryRequest(
        target,
        lowered.first,
        legacyDynamicShape(query.projection),
        lowered.scope(),
        sort = query.sort.toCanonical(),
        page = QueryPageSpec(query.pagination.index, query.pagination.size)
    )
}

@JvmSynthetic
internal fun legacyCountRequest(target: QueryTarget, condition: Condition): CountQueryRequest =
    condition.lower(target) { lowered ->
        CountQueryRequest(target, lowered.first, lowered.scope())
    }

private fun <T> Condition.lower(
    target: QueryTarget,
    transform: (Pair<QueryExpression, DeletionScope>) -> T
): T = try {
    transform(LegacyConditionLowering.lowerForGateway(this, target))
} catch (error: QueryException) {
    throw error
} catch (_: RuntimeException) {
    invalidLegacyRequest()
}

private fun Pair<QueryExpression, DeletionScope>.scope(): RequestedQueryScope =
    RequestedQueryScope(deletion = second)

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
