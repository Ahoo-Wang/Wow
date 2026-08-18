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
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.expression.ExpressionNormalizer
import me.ahoo.wow.query.expression.LegacyConditionLowering
import me.ahoo.wow.query.validation.QueryExpressionValidator
import me.ahoo.wow.query.validation.QueryStructureLimits
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
@Deprecated(
    message = "Internal WebFlux compatibility bridge.",
    level = DeprecationLevel.ERROR
)
object LegacyQueryGatewayExecution {
    @JvmSynthetic
    fun single(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: ISingleQuery,
        rewritten: ISingleQuery
    ): Mono<DynamicDocument> = Mono.defer {
        requireUnchangedSingleFields(original, rewritten)
        val additions = validatedAdditions(queryGateway, original.condition, rewritten.condition)
        val execution = singleExecution(queryGateway, target, original, additions)
        bind(queryGateway.single(execution.effectiveRequest), execution)
            .map { document -> adaptDocument(target.documentKind, document) }
    }

    @JvmSynthetic
    fun list(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: IListQuery,
        rewritten: IListQuery
    ): Flux<DynamicDocument> = Flux.defer {
        requireUnchangedListFields(original, rewritten)
        val additions = validatedAdditions(queryGateway, original.condition, rewritten.condition)
        val execution = listExecution(queryGateway, target, original, additions)
        materializeLegacyList(bind(queryGateway.list(execution.effectiveRequest), execution)) { document ->
            adaptDocument(target.documentKind, document)
        }
    }

    @JvmSynthetic
    fun page(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: IPagedQuery,
        rewritten: IPagedQuery
    ): Mono<PagedList<DynamicDocument>> = Mono.defer {
        requireUnchangedPageFields(original, rewritten)
        val additions = validatedAdditions(queryGateway, original.condition, rewritten.condition)
        val execution = pageExecution(queryGateway, target, original, additions)
        bind(queryGateway.page(execution.effectiveRequest), execution).map { page ->
            PagedList(page.total, page.items.map { document -> adaptDocument(target.documentKind, document) })
        }
    }

    @JvmSynthetic
    fun count(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: Condition,
        rewritten: Condition
    ): Mono<Long> = Mono.defer {
        val additions = validatedAdditions(queryGateway, original, rewritten)
        val execution = countExecution(queryGateway, target, original, additions)
        bind(queryGateway.count(execution.effectiveRequest), execution)
    }

    private fun singleExecution(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: ISingleQuery,
        additions: List<Condition>?
    ): LegacyQueryExecution<SingleQueryRequest<DynamicDocument>> {
        val callerRequest = legacySingleRequest(target, original)
        return execution(queryGateway, callerRequest, additions)
    }

    private fun listExecution(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: IListQuery,
        additions: List<Condition>?
    ): LegacyQueryExecution<ListQueryRequest<DynamicDocument>> {
        val callerRequest = legacyListRequest(target, original)
        return execution(queryGateway, callerRequest, additions)
    }

    private fun pageExecution(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: IPagedQuery,
        additions: List<Condition>?
    ): LegacyQueryExecution<PageQueryRequest<DynamicDocument>> {
        val callerRequest = legacyPageRequest(target, original)
        return execution(queryGateway, callerRequest, additions)
    }

    private fun countExecution(
        queryGateway: QueryGateway,
        target: QueryTarget,
        original: Condition,
        additions: List<Condition>?
    ): LegacyQueryExecution<CountQueryRequest> {
        val callerRequest = legacyCountRequest(target, original)
        return execution(queryGateway, callerRequest, additions)
    }

    private fun <R : QueryRequest> execution(
        queryGateway: QueryGateway,
        callerRequest: R,
        additions: List<Condition>?
    ): LegacyQueryExecution<R> {
        if (additions == null) {
            return LegacyQueryExecution(
                operation = operation(callerRequest),
                callerRequest = callerRequest,
                effectiveRequest = callerRequest,
                legacyExpression = null
            )
        }
        val additionCondition = if (additions.size == 1) additions.single() else Condition.and(additions)
        val loweredAddition = LegacyConditionLowering.lowerForGateway(additionCondition, callerRequest.target)
        val legacyExpression = loweredAddition.first
        val requestedScope = callerRequest.requestedScope.merge(
            additionCondition.legacyRequestedScope(loweredAddition.second)
        )
        val scopedCallerRequest = callerRequest.withRequestedScope(requestedScope)
        val unnormalizedEffectiveExpression = LogicalExpression(
            LogicalOperator.AND,
            listOf(scopedCallerRequest.expression, legacyExpression)
        )
        structureValidator(queryGateway).validateStructure(unnormalizedEffectiveExpression)
        val effectiveExpression = ExpressionNormalizer.normalize(unnormalizedEffectiveExpression)
        return LegacyQueryExecution(
            operation = operation(scopedCallerRequest),
            callerRequest = scopedCallerRequest,
            effectiveRequest = scopedCallerRequest.withExpression(effectiveExpression),
            legacyExpression = legacyExpression
        )
    }

    private fun validatedAdditions(
        queryGateway: QueryGateway,
        original: Condition,
        rewritten: Condition
    ): List<Condition>? {
        val limits = structureLimits(queryGateway)
        val validator = QueryExpressionValidator(limits)
        validateLegacyConditionStructure(rewritten, limits, validator)
        return appendOnlyAdditions(original, rewritten)
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
    private fun <R : QueryRequest> R.withRequestedScope(requestedScope: RequestedQueryScope): R = when (this) {
        is SingleQueryRequest<*> -> copy(requestedScope = requestedScope)
        is ListQueryRequest<*> -> copy(requestedScope = requestedScope)
        is PageQueryRequest<*> -> copy(requestedScope = requestedScope)
        is CountQueryRequest -> copy(requestedScope = requestedScope)
    } as R

    private fun RequestedQueryScope.merge(addition: RequestedQueryScope): RequestedQueryScope = RequestedQueryScope(
        tenantId = tenantId.mergeScopeValue(addition.tenantId),
        ownerId = ownerId.mergeScopeValue(addition.ownerId),
        spaceId = spaceId.mergeScopeValue(addition.spaceId),
        deletion = deletion.mergeDeletionScope(addition.deletion)
    )

    private fun String?.mergeScopeValue(addition: String?): String? = when {
        this == null -> addition
        addition == null || this == addition -> this
        else -> invalidRewrite()
    }

    private fun DeletionScope.mergeDeletionScope(addition: DeletionScope): DeletionScope = when {
        this == DeletionScope.DEFAULT -> addition
        addition == DeletionScope.DEFAULT || this == addition -> this
        else -> invalidRewrite()
    }

    private fun invalidRewrite(): Nothing = throw QueryException(
        QueryErrorCode.INVALID_QUERY,
        QueryStage.ADMISSION,
        QueryErrorReason.INVALID_REQUEST
    )
}

private fun structureLimits(queryGateway: QueryGateway): QueryStructureLimits =
    (queryGateway as? DefaultQueryGateway)?.legacyStructureLimits() ?: COMPATIBILITY_STRUCTURE_LIMITS

private fun structureValidator(queryGateway: QueryGateway): QueryExpressionValidator =
    QueryExpressionValidator(structureLimits(queryGateway))

private fun validateLegacyConditionStructure(
    condition: Condition,
    limits: QueryStructureLimits,
    expressionValidator: QueryExpressionValidator
) {
    val pending = ArrayDeque<Pair<Condition, Int>>()
    pending.addLast(condition to 1)
    var nodes = 0L
    var membershipItems = 0L
    try {
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeLast()
            nodes++
            if (depth > limits.maxDepth || nodes > limits.maxNodes) {
                invalidLegacyStructure()
            }
            current.enqueueChildren(pending, depth)
            membershipItems = current.validateValueStructure(membershipItems, limits, expressionValidator)
        }
    } catch (error: QueryException) {
        throw error
    } catch (_: RuntimeException) {
        invalidLegacyStructure()
    }
}

private fun Condition.enqueueChildren(pending: ArrayDeque<Pair<Condition, Int>>, depth: Int) {
    when (operator) {
        Operator.AND,
        Operator.OR,
        Operator.NOR -> {
            if (children.isEmpty()) invalidLegacyStructure()
            children.forEach { child -> pending.addLast(child to depth + 1) }
        }

        Operator.ELEM_MATCH -> {
            if (children.size != 1) invalidLegacyStructure()
            pending.addLast(children.single() to depth + 1)
        }

        else -> if (children.isNotEmpty()) invalidLegacyStructure()
    }
}

private fun Condition.validateValueStructure(
    membershipItems: Long,
    limits: QueryStructureLimits,
    expressionValidator: QueryExpressionValidator
): Long = when (operator) {
    Operator.IDS,
    Operator.AGGREGATE_IDS,
    Operator.IN,
    Operator.NOT_IN,
    Operator.ALL_IN -> (membershipItems + value.legacyCollectionSize(limits.maxMembershipItems)).also {
        if (it > limits.maxMembershipItems) invalidLegacyStructure()
    }

    Operator.BETWEEN -> membershipItems.also {
        if (value.legacyCollectionSize(2) != 2L) invalidLegacyStructure()
    }

    Operator.RAW -> membershipItems.also {
        expressionValidator.validateStructure(value as? NativeExpression ?: invalidLegacyStructure())
    }

    else -> membershipItems
}

private fun Any.legacyCollectionSize(maximum: Int): Long = when {
    this is Collection<*> -> size.toLong()
    javaClass.isArray -> java.lang.reflect.Array.getLength(this).toLong()
    this is me.ahoo.wow.api.query.expression.QueryValue.ListValue -> values.size.toLong()
    this is Iterable<*> -> {
        var size = 0L
        val iterator = iterator()
        while (iterator.hasNext()) {
            iterator.next()
            size++
            if (size > maximum) {
                return size
            }
        }
        size
    }

    else -> invalidLegacyStructure()
}

private fun invalidLegacyStructure(): Nothing = throw QueryException(
    QueryErrorCode.INVALID_QUERY,
    QueryStage.VALIDATION,
    QueryErrorReason.INVALID_REQUEST
)

private val COMPATIBILITY_STRUCTURE_LIMITS = QueryStructureLimits(
    maxDepth = 64,
    maxNodes = 10_000,
    maxMembershipItems = 10_000,
    maxNativeParameterBytes = 1_048_576
)

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
