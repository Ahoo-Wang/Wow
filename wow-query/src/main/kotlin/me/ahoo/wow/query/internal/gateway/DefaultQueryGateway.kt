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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.internal.gateway

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.SimpleDynamicDocument
import me.ahoo.wow.query.gateway.QueryAuthorityRequest
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryCall
import me.ahoo.wow.query.gateway.QueryErrorCategory
import me.ahoo.wow.query.gateway.QueryExecutionException
import me.ahoo.wow.query.gateway.QueryGateway
import me.ahoo.wow.query.gateway.QueryGatewayConfiguration
import me.ahoo.wow.query.gateway.QueryResultMaterializer
import me.ahoo.wow.query.internal.execution.BackendPage
import me.ahoo.wow.query.internal.execution.BackendRecord
import me.ahoo.wow.query.internal.execution.QueryBackendException
import me.ahoo.wow.query.internal.execution.QueryBackendFailureKind
import me.ahoo.wow.query.internal.model.QueryInput
import me.ahoo.wow.query.internal.model.QueryInvocation
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.policy.QueryAuthorityProvider
import me.ahoo.wow.query.internal.policy.QueryExecutionRequest
import me.ahoo.wow.query.internal.policy.TrustedAuthorityRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.LinkedHashMap
import me.ahoo.wow.query.gateway.QueryAuthority as PublicQueryAuthority
import me.ahoo.wow.query.gateway.QueryDocumentKind as PublicDocumentKind
import me.ahoo.wow.query.gateway.QueryExecutionMode as PublicExecutionMode
import me.ahoo.wow.query.gateway.QueryOwnerGrant as PublicOwnerGrant
import me.ahoo.wow.query.gateway.QuerySpaceGrant as PublicSpaceGrant
import me.ahoo.wow.query.gateway.QueryValidationMode as PublicValidationMode
import me.ahoo.wow.query.internal.execution.QueryGateway as InternalQueryGateway
import me.ahoo.wow.query.internal.model.QueryDocumentKind as InternalDocumentKind
import me.ahoo.wow.query.internal.model.QueryExecutionMode as InternalExecutionMode
import me.ahoo.wow.query.internal.model.QueryTarget as InternalQueryTarget
import me.ahoo.wow.query.internal.model.QueryValidationMode as InternalValidationMode
import me.ahoo.wow.query.internal.policy.QueryAuthority as InternalQueryAuthority
import me.ahoo.wow.query.internal.policy.QueryExecutionBudget as InternalExecutionBudget
import me.ahoo.wow.query.internal.policy.QueryOwnerGrant as InternalOwnerGrant
import me.ahoo.wow.query.internal.policy.QueryPurpose as InternalQueryPurpose
import me.ahoo.wow.query.internal.policy.QueryResourceScope as InternalResourceScope
import me.ahoo.wow.query.internal.policy.QuerySpaceGrant as InternalSpaceGrant

internal class DefaultQueryGateway(
    private val delegate: InternalQueryGateway,
    private val configuration: QueryGatewayConfiguration,
    resultMaterializers: Iterable<QueryResultMaterializer<*>>,
) : QueryGateway {
    private val materializers = TargetMaterializerRegistry(resultMaterializers)

    override fun single(call: QueryCall, query: ISingleQuery): Mono<DynamicDocument> =
        delegate.singleResult(
            call.toRequest(configuration),
            {
                QueryInvocation(
                    call.target.toInternal(),
                    QueryOperation.SINGLE,
                    QueryResultShape.DYNAMIC,
                    QueryInput.Single(query),
                )
            },
            BackendRecord::toDynamicDocument,
        ).mapError()

    override fun <R : Any> single(
        call: QueryCall,
        query: ISingleQuery,
        resultType: Class<R>,
    ): Mono<R> = delegate.singleResult(
        call.toRequest(configuration),
        {
            materializers.require(call.target, resultType)
            QueryInvocation(
                call.target.toInternal(),
                QueryOperation.SINGLE,
                QueryResultShape.TYPED,
                QueryInput.Single(query),
            )
        },
    ) { record -> materializers.materialize(call.target, resultType, record) }.mapError()

    override fun stream(call: QueryCall, query: IListQuery): Flux<DynamicDocument> =
        delegate.streamResult(
            call.toRequest(configuration),
            {
                QueryInvocation(
                    call.target.toInternal(),
                    QueryOperation.STREAM,
                    QueryResultShape.DYNAMIC,
                    QueryInput.Stream(query),
                )
            },
            BackendRecord::toDynamicDocument,
        ).mapError()

    override fun <R : Any> stream(
        call: QueryCall,
        query: IListQuery,
        resultType: Class<R>,
    ): Flux<R> = delegate.streamResult(
        call.toRequest(configuration),
        {
            materializers.require(call.target, resultType)
            QueryInvocation(
                call.target.toInternal(),
                QueryOperation.STREAM,
                QueryResultShape.TYPED,
                QueryInput.Stream(query),
            )
        },
    ) { record -> materializers.materialize(call.target, resultType, record) }.mapError()

    override fun page(call: QueryCall, query: IPagedQuery): Mono<PagedList<DynamicDocument>> =
        delegate.pageResult(
            call.toRequest(configuration),
            {
                QueryInvocation(
                    call.target.toInternal(),
                    QueryOperation.PAGE,
                    QueryResultShape.DYNAMIC,
                    QueryInput.Page(query),
                )
            },
            BackendPage::toDynamicPage,
        ).mapError()

    override fun <R : Any> page(
        call: QueryCall,
        query: IPagedQuery,
        resultType: Class<R>,
    ): Mono<PagedList<R>> = delegate.pageResult(
        call.toRequest(configuration),
        {
            materializers.require(call.target, resultType)
            QueryInvocation(
                call.target.toInternal(),
                QueryOperation.PAGE,
                QueryResultShape.TYPED,
                QueryInput.Page(query),
            )
        },
    ) { page ->
        PagedList(
            page.total,
            page.records.map { record -> materializers.materialize(call.target, resultType, record) },
        )
    }.mapError()

    override fun count(call: QueryCall, condition: Condition): Mono<Long> =
        delegate.count(call.toRequest(configuration)) {
            QueryInvocation(
                call.target.toInternal(),
                QueryOperation.COUNT,
                QueryResultShape.COUNT,
                QueryInput.Count(condition),
            )
        }.mapError()
}

internal class GatewayAuthorityProvider(
    private val trustedAuthorityChannel: TrustedAuthorityChannel,
    private val resolver: QueryAuthorityResolver,
) : QueryAuthorityProvider {
    override fun resolve(request: QueryExecutionRequest): Mono<InternalQueryAuthority> =
        Mono.deferContextual { context ->
            trustedAuthorityChannel.read(context)?.let { authority ->
                Mono.just(authority.toInternal())
            } ?: Mono.defer {
                resolver.resolve(request.toPublic()).map(PublicQueryAuthority::toInternal)
            }
        }.onErrorMap(::mapTrustedAuthorityRejection)
}

private fun mapTrustedAuthorityRejection(error: Throwable): Throwable {
    if (error !is QueryExecutionException || error.category != QueryErrorCategory.ACCESS_DENIED) {
        return error
    }
    val (path, code) = when {
        error.path == AUTHORITY_PATH && error.code == QueryRejectionCode.AUTHORITY_REQUIRED.name ->
            QueryRejectionPath.ROOT.property("executionContext").property("authority") to
                QueryRejectionCode.AUTHORITY_REQUIRED

        error.path == LEGACY_GRANT_PATH && error.code == QueryRejectionCode.LEGACY_CALLER_NOT_ALLOWED.name ->
            QueryRejectionPath.ROOT.property("executionContext").property("legacyGrant") to
                QueryRejectionCode.LEGACY_CALLER_NOT_ALLOWED

        error.path == TRANSPORT_PATH && error.code == QueryRejectionCode.QUERY_TRANSPORT_AUTHORITY_MISMATCH.name ->
            QueryRejectionPath.ROOT.property("executionContext").property("transport") to
                QueryRejectionCode.QUERY_TRANSPORT_AUTHORITY_MISMATCH

        else -> return error
    }
    return TrustedAuthorityRejectedException(path, code, error)
}

private fun QueryCall.toRequest(configuration: QueryGatewayConfiguration): QueryExecutionRequest =
    QueryExecutionRequest(
        target = target.toInternal(),
        purpose = InternalQueryPurpose(purpose.value),
        executionMode = configuration.executionMode.toInternal(),
        validationMode = configuration.validationMode.toInternal(),
        resourceScope = InternalResourceScope(
            resourceScope.tenantId,
            resourceScope.ownerId,
            resourceScope.spaceId,
        ),
        deadline = deadline,
        budget = InternalExecutionBudget(
            maxReturnedRecords = budget.maxReturnedRecords,
        ),
    )

private fun QueryExecutionRequest.toPublic(): QueryAuthorityRequest =
    QueryAuthorityRequest(
        call = QueryCall(
            target = me.ahoo.wow.query.gateway.QueryTarget(
                target.namedAggregate,
                when (target.documentKind) {
                    InternalDocumentKind.SNAPSHOT -> PublicDocumentKind.SNAPSHOT
                    InternalDocumentKind.EVENT_STREAM -> PublicDocumentKind.EVENT_STREAM
                },
            ),
            purpose = me.ahoo.wow.query.gateway.QueryPurpose(purpose.value),
            resourceScope = me.ahoo.wow.query.gateway.QueryResourceScope(
                resourceScope.tenantId,
                resourceScope.ownerId,
                resourceScope.spaceId,
            ),
            deadline = deadline,
            budget = me.ahoo.wow.query.gateway.QueryExecutionBudget(
                maxReturnedRecords = budget.maxReturnedRecords,
            ),
        ),
        executionMode = executionMode.toPublic(),
        validationMode = validationMode.toPublic(),
    )

private fun PublicQueryAuthority.toInternal(): InternalQueryAuthority =
    when (this) {
        is PublicQueryAuthority.Subject -> InternalQueryAuthority.Subject(
            subjectId,
            tenantId,
            ownerGrant.toInternal(),
            spaceGrant.toInternal(),
        )

        is PublicQueryAuthority.Service -> InternalQueryAuthority.Service(
            serviceId,
            tenantId,
            purposes.mapTo(LinkedHashSet()) { InternalQueryPurpose(it.value) },
        )

        is PublicQueryAuthority.System -> InternalQueryAuthority.System(principalId, justification)

        is PublicQueryAuthority.Legacy -> InternalQueryAuthority.Legacy(
            me.ahoo.wow.query.internal.policy.LegacyQueryGrant(
                me.ahoo.wow.query.internal.policy.LegacyQueryCallerId(grant.callerId),
                grant.target.toInternal(),
                me.ahoo.wow.query.internal.policy.QueryPurpose(grant.purpose.value),
                grant.executionMode.toInternal(),
                me.ahoo.wow.query.internal.policy.QueryResourceScope(
                    grant.resourceScope.tenantId,
                    grant.resourceScope.ownerId,
                    grant.resourceScope.spaceId,
                ),
            ),
        )
    }

private fun PublicOwnerGrant.toInternal(): InternalOwnerGrant =
    when (this) {
        PublicOwnerGrant.Unrestricted -> InternalOwnerGrant.Unrestricted
        is PublicOwnerGrant.Only -> InternalOwnerGrant.Only(ownerId)
    }

private fun PublicSpaceGrant.toInternal(): InternalSpaceGrant =
    when (this) {
        PublicSpaceGrant.Unrestricted -> InternalSpaceGrant.Unrestricted
        PublicSpaceGrant.DenyAll -> InternalSpaceGrant.DenyAll
        is PublicSpaceGrant.AllowList -> InternalSpaceGrant.AllowList(spaceIds)
    }

private fun me.ahoo.wow.query.gateway.QueryTarget.toInternal(): InternalQueryTarget =
    InternalQueryTarget(
        namedAggregate,
        when (documentKind) {
            PublicDocumentKind.SNAPSHOT -> InternalDocumentKind.SNAPSHOT
            PublicDocumentKind.EVENT_STREAM -> InternalDocumentKind.EVENT_STREAM
        },
    )

private fun PublicExecutionMode.toInternal(): InternalExecutionMode =
    when (this) {
        PublicExecutionMode.LEGACY -> InternalExecutionMode.LEGACY
        PublicExecutionMode.SHADOW -> InternalExecutionMode.SHADOW
        PublicExecutionMode.PLANNED -> InternalExecutionMode.PLANNED
    }

private fun InternalExecutionMode.toPublic(): PublicExecutionMode =
    when (this) {
        InternalExecutionMode.LEGACY -> PublicExecutionMode.LEGACY
        InternalExecutionMode.SHADOW -> PublicExecutionMode.SHADOW
        InternalExecutionMode.PLANNED -> PublicExecutionMode.PLANNED
    }

private fun PublicValidationMode.toInternal(): InternalValidationMode =
    when (this) {
        PublicValidationMode.COMPATIBLE -> InternalValidationMode.COMPATIBLE
        PublicValidationMode.STRICT -> InternalValidationMode.STRICT
    }

private fun InternalValidationMode.toPublic(): PublicValidationMode =
    when (this) {
        InternalValidationMode.COMPATIBLE -> PublicValidationMode.COMPATIBLE
        InternalValidationMode.STRICT -> PublicValidationMode.STRICT
    }

private fun BackendRecord.toDynamicDocument(): DynamicDocument = document.toMutableMap().let(::SimpleDynamicDocument)

private fun BackendPage.toDynamicPage(): PagedList<DynamicDocument> =
    PagedList(total, records.map(BackendRecord::toDynamicDocument))

private fun NormalizedValue.ObjectValue.toMutableMap(): MutableMap<String, Any?> {
    val copy = LinkedHashMap<String, Any?>(values.size)
    values.forEach { (key, value) -> copy[key] = value.toMutableValue() }
    return copy
}

private fun NormalizedValue.toMutableValue(): Any? =
    when (this) {
        NormalizedValue.Null -> null
        is NormalizedValue.BooleanValue -> value
        is NormalizedValue.Text -> value
        is NormalizedValue.Int64 -> value
        is NormalizedValue.Decimal -> value
        is NormalizedValue.InstantValue -> value
        is NormalizedValue.Bytes -> toByteArray()
        is NormalizedValue.ListValue -> values.mapTo(ArrayList(values.size), NormalizedValue::toMutableValue)
        is NormalizedValue.ObjectValue -> toMutableMap()
    }

private class TargetMaterializerRegistry(registrations: Iterable<QueryResultMaterializer<*>>) {
    private val registrations: Map<me.ahoo.wow.query.gateway.QueryTarget, QueryResultMaterializer<*>>

    init {
        val materialized = registrations.toList()
        require(materialized.map(QueryResultMaterializer<*>::target).distinct().size == materialized.size) {
            "Query result materializer targets must be unique."
        }
        val copy = LinkedHashMap<me.ahoo.wow.query.gateway.QueryTarget, QueryResultMaterializer<*>>(materialized.size)
        materialized.sortedWith(MATERIALIZER_COMPARATOR).forEach { registration ->
            copy[registration.target] = registration
        }
        this.registrations = java.util.Collections.unmodifiableMap(copy)
    }

    fun <R : Any> require(target: me.ahoo.wow.query.gateway.QueryTarget, resultType: Class<R>) {
        val registration = registrations[target]
        if (registration == null || registration.resultType != resultType) {
            rejectMaterialization()
        }
    }

    fun <R : Any> materialize(
        target: me.ahoo.wow.query.gateway.QueryTarget,
        resultType: Class<R>,
        record: BackendRecord,
    ): R {
        val registration = requireRegistration(target, resultType)
        return resultType.castMaterialized(registration.materializeSafely(record))
    }

    private fun <R : Any> requireRegistration(
        target: me.ahoo.wow.query.gateway.QueryTarget,
        resultType: Class<R>,
    ): QueryResultMaterializer<*> = registrations[target]?.takeIf { registration ->
        registration.resultType == resultType
    } ?: rejectMaterialization()
}

@Suppress("TooGenericExceptionCaught")
private fun QueryResultMaterializer<*>.materializeSafely(record: BackendRecord): Any =
    try {
        materialize(record.identity, record.toDynamicDocument())
    } catch (error: RuntimeException) {
        rejectMaterialization(error)
    }

private fun <R : Any> Class<R>.castMaterialized(value: Any): R =
    try {
        cast(value)
    } catch (error: ClassCastException) {
        rejectMaterialization(error)
    }

private fun rejectMaterialization(cause: Throwable? = null): Nothing =
    throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE, cause)

private val MATERIALIZER_COMPARATOR = compareBy<QueryResultMaterializer<*>>(
    { registration -> registration.target.namedAggregate.contextName },
    { registration -> registration.target.namedAggregate.aggregateName },
    { registration -> registration.target.documentKind.name },
)

private fun <T : Any> Mono<T>.mapError(): Mono<T> = onErrorMap(::toPublicException)

private fun <T : Any> Flux<T>.mapError(): Flux<T> = onErrorMap(::toPublicException)

private fun toPublicException(error: Throwable): Throwable {
    if (error is QueryExecutionException) {
        return error
    }
    val rejected = error as? QueryRejectedException
        ?: return QueryExecutionException(
            QueryErrorCategory.INTERNAL_FAILURE,
            "$",
            "UNEXPECTED_QUERY_FAILURE",
            error,
        )
    return QueryExecutionException(
        category = rejected.rejection.category.toPublic(),
        path = rejected.rejection.path.toString(),
        code = rejected.rejection.code.name,
        cause = rejected,
    )
}

private fun me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.toPublic(): QueryErrorCategory =
    when (this) {
        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.ACCESS_DENIED -> QueryErrorCategory.ACCESS_DENIED
        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.INVALID_QUERY -> QueryErrorCategory.INVALID_QUERY
        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.INVALID_CURSOR -> QueryErrorCategory.INVALID_CURSOR
        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.BUDGET_EXCEEDED -> QueryErrorCategory.BUDGET_EXCEEDED
        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.UNSUPPORTED_FEATURE ->
            QueryErrorCategory.UNSUPPORTED_FEATURE

        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.BACKEND_UNAVAILABLE ->
            QueryErrorCategory.BACKEND_UNAVAILABLE

        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.BACKEND_TIMEOUT -> QueryErrorCategory.BACKEND_TIMEOUT
        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.INCOMPLETE_RESULT ->
            QueryErrorCategory.INCOMPLETE_RESULT

        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.MAPPING_FAILURE -> QueryErrorCategory.MAPPING_FAILURE
        me.ahoo.wow.query.internal.rejection.QueryRejectionCategory.INTERNAL_FAILURE -> QueryErrorCategory.INTERNAL_FAILURE
    }

private const val AUTHORITY_PATH = "$.executionContext.authority"
private const val LEGACY_GRANT_PATH = "$.executionContext.legacyGrant"
private const val TRANSPORT_PATH = "$.executionContext.transport"
