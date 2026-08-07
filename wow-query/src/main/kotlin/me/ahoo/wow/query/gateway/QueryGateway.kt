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

@file:OptIn(ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.gateway

import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.Collections

/**
 * Marks the additive, evolving Query Gateway composition API.
 *
 * Existing [me.ahoo.wow.query.QueryService] contracts remain the supported compatibility surface. Applications should
 * normally obtain Gateway-backed services from Spring rather than construct the runtime directly.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "The Query Gateway composition API is experimental and may evolve before the next major release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class ExperimentalQueryGatewayApi

@ExperimentalQueryGatewayApi
enum class QueryDocumentKind {
    SNAPSHOT,
    EVENT_STREAM,
}

@ExperimentalQueryGatewayApi
enum class QueryExecutionMode {
    LEGACY,
    SHADOW,
    PLANNED,
}

@ExperimentalQueryGatewayApi
enum class QueryValidationMode {
    COMPATIBLE,
    STRICT,
}

@ExperimentalQueryGatewayApi
class QueryTarget(
    namedAggregate: NamedAggregate,
    val documentKind: QueryDocumentKind,
) {
    val namedAggregate: MaterializedNamedAggregate = namedAggregate.materialize()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QueryTarget && namedAggregate == other.namedAggregate && documentKind == other.documentKind

    override fun hashCode(): Int = 31 * namedAggregate.hashCode() + documentKind.hashCode()

    override fun toString(): String = "QueryTarget(namedAggregate=$namedAggregate, documentKind=$documentKind)"
}

@ExperimentalQueryGatewayApi
class QueryPurpose(val value: String) {
    init {
        requireIdentifier(value, "Query purpose")
    }

    override fun equals(other: Any?): Boolean = this === other || other is QueryPurpose && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

@ExperimentalQueryGatewayApi
data class QueryResourceScope(
    val tenantId: String? = null,
    val ownerId: String? = null,
    val spaceId: String? = null,
) {
    init {
        tenantId?.let { requireIdentifier(it, "Resource tenant id") }
        ownerId?.let { requireIdentifier(it, "Resource owner id") }
        spaceId?.let { requireIdentifier(it, "Resource space id") }
    }
}

@ExperimentalQueryGatewayApi
data class QueryExecutionBudget(
    val maxReturnedRecords: Long? = null,
) {
    init {
        require(maxReturnedRecords == null || maxReturnedRecords > 0)
    }
}

@ExperimentalQueryGatewayApi
data class QueryCall(
    val target: QueryTarget,
    val purpose: QueryPurpose,
    val resourceScope: QueryResourceScope = QueryResourceScope(),
    val deadline: Instant? = null,
    val budget: QueryExecutionBudget = QueryExecutionBudget(),
)

@ExperimentalQueryGatewayApi
data class QueryGatewayConfiguration(
    val executionMode: QueryExecutionMode = QueryExecutionMode.LEGACY,
    val validationMode: QueryValidationMode = QueryValidationMode.COMPATIBLE,
)

@ExperimentalQueryGatewayApi
sealed interface QueryOwnerGrant {
    data object Unrestricted : QueryOwnerGrant

    data class Only(val ownerId: String) : QueryOwnerGrant {
        init {
            requireIdentifier(ownerId, "Authority owner id")
        }
    }
}

@ExperimentalQueryGatewayApi
sealed interface QuerySpaceGrant {
    data object Unrestricted : QuerySpaceGrant

    data object DenyAll : QuerySpaceGrant

    class AllowList(spaceIds: Iterable<String>) : QuerySpaceGrant {
        val spaceIds: Set<String> = immutableIdentifiers(spaceIds, "Authority space id")

        init {
            require(this.spaceIds.isNotEmpty()) {
                "Authority space allow-list must not be empty."
            }
        }

        override fun equals(other: Any?): Boolean = this === other || other is AllowList && spaceIds == other.spaceIds

        override fun hashCode(): Int = spaceIds.hashCode()
    }
}

@ExperimentalQueryGatewayApi
sealed interface QueryAuthority {
    val principalId: String

    class Subject(
        val subjectId: String,
        val tenantId: String,
        val ownerGrant: QueryOwnerGrant = QueryOwnerGrant.Unrestricted,
        val spaceGrant: QuerySpaceGrant = QuerySpaceGrant.Unrestricted,
    ) : QueryAuthority {
        override val principalId: String = subjectId

        init {
            requireIdentifier(subjectId, "Authority subject id")
            requireIdentifier(tenantId, "Authority tenant id")
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Subject &&
                subjectId == other.subjectId &&
                tenantId == other.tenantId &&
                ownerGrant == other.ownerGrant &&
                spaceGrant == other.spaceGrant

        override fun hashCode(): Int {
            var result = subjectId.hashCode()
            result = 31 * result + tenantId.hashCode()
            result = 31 * result + ownerGrant.hashCode()
            result = 31 * result + spaceGrant.hashCode()
            return result
        }
    }

    class Service(
        val serviceId: String,
        val tenantId: String,
        purposes: Iterable<QueryPurpose>,
    ) : QueryAuthority {
        override val principalId: String = serviceId
        val purposes: Set<QueryPurpose> = Collections.unmodifiableSet(
            LinkedHashSet(purposes.sortedBy(QueryPurpose::value)),
        )

        init {
            requireIdentifier(serviceId, "Authority service id")
            requireIdentifier(tenantId, "Authority tenant id")
            require(this.purposes.isNotEmpty()) {
                "Service authority purposes must not be empty."
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Service &&
                serviceId == other.serviceId &&
                tenantId == other.tenantId &&
                purposes == other.purposes

        override fun hashCode(): Int {
            var result = serviceId.hashCode()
            result = 31 * result + tenantId.hashCode()
            result = 31 * result + purposes.hashCode()
            return result
        }
    }

    data class System(
        override val principalId: String,
        val justification: String,
    ) : QueryAuthority {
        init {
            requireIdentifier(principalId, "Authority principal id")
            requireIdentifier(justification, "System authority justification")
        }
    }

    data class Legacy(val grant: QueryLegacyGrant) : QueryAuthority {
        override val principalId: String = grant.callerId
    }
}

/** Exact, pre-registered compatibility grant for a trusted process-internal caller. */
@ExperimentalQueryGatewayApi
data class QueryLegacyGrant(
    val callerId: String,
    val target: QueryTarget,
    val purpose: QueryPurpose,
    val executionMode: QueryExecutionMode,
    val resourceScope: QueryResourceScope,
) {
    init {
        requireIdentifier(callerId, "Legacy query caller id")
    }
}

@ExperimentalQueryGatewayApi
data class QueryAuthorityRequest(
    val call: QueryCall,
    val executionMode: QueryExecutionMode,
    val validationMode: QueryValidationMode,
)

@ExperimentalQueryGatewayApi
data class QueryTrustedContextRequest(
    val callRequest: QueryCallResolutionRequest,
    val executionMode: QueryExecutionMode,
    val validationMode: QueryValidationMode,
)

@ExperimentalQueryGatewayApi
data class QueryTrustedContext(
    val call: QueryCall,
    val authority: QueryAuthority,
)

@ExperimentalQueryGatewayApi
fun interface QueryAuthorityResolver {
    /** Called once for every subscription. Empty and error signals are fail-closed by the Gateway. */
    fun resolve(request: QueryAuthorityRequest): Mono<QueryAuthority>
}

/** A trusted context source that atomically resolves both halves of one compatibility-facade subscription. */
@ExperimentalQueryGatewayApi
fun interface QueryTrustedContextResolver {
    fun resolve(request: QueryTrustedContextRequest): Mono<QueryTrustedContext>
}

/** Ordered composition. An error from an applicable resolver is fail-closed and never falls through. */
@ExperimentalQueryGatewayApi
class CompositeQueryTrustedContextResolver(resolvers: Iterable<QueryTrustedContextResolver>) :
    QueryTrustedContextResolver {
    private val resolvers = resolvers.toList()

    init {
        require(this.resolvers.isNotEmpty()) {
            "At least one trusted query context resolver is required."
        }
    }

    override fun resolve(request: QueryTrustedContextRequest): Mono<QueryTrustedContext> =
        Flux.fromIterable(resolvers)
            .concatMap { resolver -> Mono.defer { resolver.resolve(request) } }
            .next()
}

/**
 * Resolves an exact registered [QueryLegacyGrant] from a trusted process-internal Reactor context marker.
 *
 * This is a one-version migration bridge. The caller marker is not a System authority and cannot change the grant's
 * target, purpose, execution mode or resource scope.
 */
@ExperimentalQueryGatewayApi
class QueryLegacyContextResolver(grants: Iterable<QueryLegacyGrant>) : QueryTrustedContextResolver {
    private val grantsByCallerAndTarget: Map<LegacyGrantKey, QueryLegacyGrant>

    init {
        val materialized = grants.toList()
        val indexed = materialized.associateBy { LegacyGrantKey(it.callerId, it.target) }
        require(indexed.size == materialized.size) {
            "Legacy query grants must be unique by caller id and target."
        }
        grantsByCallerAndTarget = Collections.unmodifiableMap(LinkedHashMap(indexed))
    }

    override fun resolve(request: QueryTrustedContextRequest): Mono<QueryTrustedContext> = Mono.deferContextual { context ->
        val callerId = context.getOrDefault<String>(LEGACY_CALLER_CONTEXT_KEY, null)
            ?: return@deferContextual Mono.empty()
        val grant = grantsByCallerAndTarget[LegacyGrantKey(callerId, request.callRequest.target)]
            ?: return@deferContextual Mono.error(legacyGrantRejected())
        if (request.executionMode != grant.executionMode ||
            request.callRequest.target != grant.target
        ) {
            return@deferContextual Mono.error(legacyGrantRejected())
        }
        val call = QueryCall(grant.target, grant.purpose, grant.resourceScope)
        Mono.just(QueryTrustedContext(call, QueryAuthority.Legacy(grant)))
    }
}

@ExperimentalQueryGatewayApi
fun <T : Any> Mono<T>.withLegacyQueryCaller(callerId: String): Mono<T> {
    requireIdentifier(callerId, "Legacy query caller id")
    return contextWrite { context -> context.put(LEGACY_CALLER_CONTEXT_KEY, callerId) }
}

@ExperimentalQueryGatewayApi
fun <T : Any> Flux<T>.withLegacyQueryCaller(callerId: String): Flux<T> {
    requireIdentifier(callerId, "Legacy query caller id")
    return contextWrite { context -> context.put(LEGACY_CALLER_CONTEXT_KEY, callerId) }
}

@ExperimentalQueryGatewayApi
interface QueryGateway {
    fun single(call: QueryCall, query: ISingleQuery): Mono<DynamicDocument>

    fun <R : Any> single(call: QueryCall, query: ISingleQuery, resultType: Class<R>): Mono<R>

    fun stream(call: QueryCall, query: IListQuery): Flux<DynamicDocument>

    fun <R : Any> stream(call: QueryCall, query: IListQuery, resultType: Class<R>): Flux<R>

    fun page(call: QueryCall, query: IPagedQuery): Mono<PagedList<DynamicDocument>>

    fun <R : Any> page(call: QueryCall, query: IPagedQuery, resultType: Class<R>): Mono<PagedList<R>>

    fun count(call: QueryCall, condition: Condition): Mono<Long>
}

/** A target-bound typed materializer. Registration is unique per exact [QueryTarget]. */
@ExperimentalQueryGatewayApi
class QueryResultMaterializer<R : Any>(
    val target: QueryTarget,
    val resultType: Class<R>,
    private val materialize: (identity: String, document: DynamicDocument) -> R,
) {
    fun materialize(identity: String, document: DynamicDocument): R = materialize.invoke(identity, document)
}

@ExperimentalQueryGatewayApi
enum class QueryErrorCategory {
    ACCESS_DENIED,
    INVALID_QUERY,
    INVALID_CURSOR,
    BUDGET_EXCEEDED,
    UNSUPPORTED_FEATURE,
    BACKEND_UNAVAILABLE,
    BACKEND_TIMEOUT,
    INCOMPLETE_RESULT,
    MAPPING_FAILURE,
    INTERNAL_FAILURE,
}

@ExperimentalQueryGatewayApi
class QueryExecutionException(
    val category: QueryErrorCategory,
    val path: String,
    val code: String,
    cause: Throwable? = null,
) : WowException(
    errorCode = "Query.${category.name}.$code",
    errorMsg = category.safeMessage(),
    cause = cause,
    bindingErrors = listOf(BindingError(path, code)),
)

private fun QueryErrorCategory.safeMessage(): String =
    when (this) {
        QueryErrorCategory.ACCESS_DENIED -> "Query access was denied."
        QueryErrorCategory.INVALID_QUERY -> "The query is invalid."
        QueryErrorCategory.INVALID_CURSOR -> "The query cursor is invalid."
        QueryErrorCategory.BUDGET_EXCEEDED -> "The query budget was exceeded."
        QueryErrorCategory.UNSUPPORTED_FEATURE -> "The query uses an unsupported feature."
        QueryErrorCategory.BACKEND_UNAVAILABLE -> "The query backend is unavailable."
        QueryErrorCategory.BACKEND_TIMEOUT -> "The query backend timed out."
        QueryErrorCategory.INCOMPLETE_RESULT -> "The query result is incomplete."
        QueryErrorCategory.MAPPING_FAILURE -> "The query result could not be mapped."
        QueryErrorCategory.INTERNAL_FAILURE -> "The query failed unexpectedly."
    }

private fun requireIdentifier(value: String, name: String) {
    require(value.isNotBlank()) {
        "$name must not be blank."
    }
    require(value.none(Char::isISOControl)) {
        "$name must not contain control characters."
    }
    require(value.length <= MAX_IDENTIFIER_LENGTH) {
        "$name must not exceed $MAX_IDENTIFIER_LENGTH characters."
    }
}

private fun immutableIdentifiers(values: Iterable<String>, name: String): Set<String> {
    val materialized = values.toList()
    materialized.forEach { requireIdentifier(it, name) }
    return Collections.unmodifiableSet(LinkedHashSet(materialized.sorted()))
}

private data class LegacyGrantKey(val callerId: String, val target: QueryTarget)

private fun legacyGrantRejected(): QueryExecutionException = QueryExecutionException(
    category = QueryErrorCategory.ACCESS_DENIED,
    path = "$.executionContext.legacyGrant",
    code = "LEGACY_CALLER_NOT_ALLOWED",
)

private const val LEGACY_CALLER_CONTEXT_KEY = "me.ahoo.wow.query.legacy.caller"

private const val MAX_IDENTIFIER_LENGTH = 512
