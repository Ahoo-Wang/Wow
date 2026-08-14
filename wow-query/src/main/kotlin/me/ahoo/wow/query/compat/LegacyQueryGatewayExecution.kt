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
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.expression.ExpressionNormalizer
import me.ahoo.wow.query.expression.LegacyConditionLowering
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * Narrow compatibility execution path used by legacy HTTP query DTOs.
 *
 * It accepts only an unchanged condition or a structurally proven append-only rewrite. The caller expression and
 * appended legacy expression are carried separately to Wow's default Gateway. Other [QueryGateway] implementations
 * receive the equivalent effective `AND` expression and therefore cannot silently omit the enrichment.
 */
object LegacyQueryGatewayExecution {
    fun single(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: ISingleQuery,
        rewritten: ISingleQuery
    ): Mono<DynamicDocument> = Mono.defer {
        requireUnchangedSingleFields(original, rewritten)
        val execution = singleExecution(target, original, rewritten)
        bind(queryGateway.single(execution.effectiveRequest), execution)
            .map { document -> adaptDocument(target.documentKind, document) }
    }

    fun list(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: IListQuery,
        rewritten: IListQuery
    ): Flux<DynamicDocument> = Flux.defer {
        requireUnchangedListFields(original, rewritten)
        val execution = listExecution(target, original, rewritten)
        materializeLegacyList(bind(queryGateway.list(execution.effectiveRequest), execution)) { document ->
            adaptDocument(target.documentKind, document)
        }
    }

    fun page(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: IPagedQuery,
        rewritten: IPagedQuery
    ): Mono<PagedList<DynamicDocument>> = Mono.defer {
        requireUnchangedPageFields(original, rewritten)
        val execution = pageExecution(target, original, rewritten)
        bind(queryGateway.page(execution.effectiveRequest), execution).map { page ->
            PagedList(page.total, page.items.map { document -> adaptDocument(target.documentKind, document) })
        }
    }

    fun count(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: Condition,
        rewritten: Condition
    ): Mono<Long> = Mono.defer {
        val execution = countExecution(target, original, rewritten)
        bind(queryGateway.count(execution.effectiveRequest), execution)
    }

    private fun singleExecution(
        target: QueryTarget,
        original: ISingleQuery,
        rewritten: ISingleQuery
    ): LegacyQueryExecution<SingleQueryRequest<DynamicDocument>> {
        val callerRequest = legacySingleRequest(target, original)
        val rewrittenRequest = legacySingleRequest(target, rewritten)
        return execution(
            callerRequest.copy(requestedScope = rewrittenRequest.requestedScope),
            original.condition,
            rewritten.condition
        )
    }

    private fun listExecution(
        target: QueryTarget,
        original: IListQuery,
        rewritten: IListQuery
    ): LegacyQueryExecution<ListQueryRequest<DynamicDocument>> {
        val callerRequest = legacyListRequest(target, original)
        val rewrittenRequest = legacyListRequest(target, rewritten)
        return execution(
            callerRequest.copy(requestedScope = rewrittenRequest.requestedScope),
            original.condition,
            rewritten.condition
        )
    }

    private fun pageExecution(
        target: QueryTarget,
        original: IPagedQuery,
        rewritten: IPagedQuery
    ): LegacyQueryExecution<PageQueryRequest<DynamicDocument>> {
        val callerRequest = legacyPageRequest(target, original)
        val rewrittenRequest = legacyPageRequest(target, rewritten)
        return execution(
            callerRequest.copy(requestedScope = rewrittenRequest.requestedScope),
            original.condition,
            rewritten.condition
        )
    }

    private fun countExecution(
        target: QueryTarget,
        original: Condition,
        rewritten: Condition
    ): LegacyQueryExecution<CountQueryRequest> {
        val callerRequest = legacyCountRequest(target, original)
        val rewrittenRequest = legacyCountRequest(target, rewritten)
        return execution(callerRequest.copy(requestedScope = rewrittenRequest.requestedScope), original, rewritten)
    }

    private fun <R : QueryRequest> execution(
        callerRequest: R,
        original: Condition,
        rewritten: Condition
    ): LegacyQueryExecution<R> {
        val additions = appendOnlyAdditions(original, rewritten)
        if (additions == null) {
            return LegacyQueryExecution(
                operation = operation(callerRequest),
                callerRequest = callerRequest,
                effectiveRequest = callerRequest,
                legacyExpression = null
            )
        }
        val additionCondition = if (additions.size == 1) additions.single() else Condition.and(additions)
        val legacyExpression = LegacyConditionLowering.lowerForGateway(additionCondition, callerRequest.target).first
        val effectiveExpression = ExpressionNormalizer.logical(
            LogicalOperator.AND,
            listOf(callerRequest.expression, legacyExpression)
        )
        return LegacyQueryExecution(
            operation = operation(callerRequest),
            callerRequest = callerRequest,
            effectiveRequest = callerRequest.withExpression(effectiveExpression),
            legacyExpression = legacyExpression
        )
    }

    private fun appendOnlyAdditions(original: Condition, rewritten: Condition): List<Condition>? {
        if (rewritten == original) {
            return null
        }
        if (rewritten.operator != Operator.AND || rewritten.children.size < 2 || rewritten.children.first() != original) {
            invalidRewrite()
        }
        return rewritten.children.drop(1)
    }

    private fun requireUnchangedSingleFields(original: ISingleQuery, rewritten: ISingleQuery) {
        if (original.projection != rewritten.projection || original.sort != rewritten.sort) {
            invalidRewrite()
        }
    }

    private fun requireUnchangedListFields(original: IListQuery, rewritten: IListQuery) {
        if (original.projection != rewritten.projection || original.sort != rewritten.sort || original.limit != rewritten.limit) {
            invalidRewrite()
        }
    }

    private fun requireUnchangedPageFields(original: IPagedQuery, rewritten: IPagedQuery) {
        if (
            original.projection != rewritten.projection || original.sort != rewritten.sort ||
            original.pagination != rewritten.pagination
        ) {
            invalidRewrite()
        }
    }

    private fun adaptDocument(kind: QueryDocumentKind, document: DynamicDocument): DynamicDocument = when (kind) {
        QueryDocumentKind.SNAPSHOT -> adaptLegacySnapshotDocument(document)
        QueryDocumentKind.EVENT_STREAM -> adaptLegacyEventDocument(document)
    }

    private fun operation(request: QueryRequest): QueryOperation = when (request) {
        is SingleQueryRequest<*> -> QueryOperation.SINGLE
        is ListQueryRequest<*> -> QueryOperation.LIST
        is PageQueryRequest<*> -> QueryOperation.PAGE
        is CountQueryRequest -> QueryOperation.COUNT
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R : QueryRequest> R.withExpression(expression: QueryExpression): R = when (this) {
        is SingleQueryRequest<*> -> copy(expression = expression)
        is ListQueryRequest<*> -> copy(expression = expression)
        is PageQueryRequest<*> -> copy(expression = expression)
        is CountQueryRequest -> copy(expression = expression)
    } as R

    private fun invalidRewrite(): Nothing = throw QueryException(
        QueryErrorCode.INVALID_QUERY,
        QueryStage.ADMISSION,
        QueryErrorReason.INVALID_REQUEST
    )
}

internal class LegacyQueryExecution<R : QueryRequest>(
    val operation: QueryOperation,
    val callerRequest: R,
    val effectiveRequest: R,
    val legacyExpression: QueryExpression?
)

private object LegacyQueryExecutionContextKey

private fun <T : Any> bind(publisher: Mono<T>, execution: LegacyQueryExecution<*>): Mono<T> =
    if (execution.legacyExpression == null) {
        publisher
    } else {
        publisher.contextWrite {
            it.put(LegacyQueryExecutionContextKey, execution)
        }
    }

private fun <T : Any> bind(publisher: Flux<T>, execution: LegacyQueryExecution<*>): Flux<T> =
    if (execution.legacyExpression == null) {
        publisher
    } else {
        publisher.contextWrite {
            it.put(LegacyQueryExecutionContextKey, execution)
        }
    }

@JvmSynthetic
internal fun <R : QueryRequest> ContextView.legacyQueryExecution(
    effectiveRequest: R,
    operation: QueryOperation
): LegacyQueryExecution<R>? {
    val execution = getOrEmpty<LegacyQueryExecution<*>>(LegacyQueryExecutionContextKey).orElse(null) ?: return null
    if (execution.effectiveRequest !== effectiveRequest || execution.operation != operation) {
        throw QueryException(
            QueryErrorCode.INVALID_QUERY,
            QueryStage.ADMISSION,
            QueryErrorReason.INVALID_REQUEST
        )
    }
    @Suppress("UNCHECKED_CAST")
    return execution as LegacyQueryExecution<R>
}
