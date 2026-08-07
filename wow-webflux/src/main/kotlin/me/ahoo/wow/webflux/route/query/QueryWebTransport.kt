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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.filter.QueryHandler
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi
import me.ahoo.wow.query.gateway.QueryAuthority
import me.ahoo.wow.query.gateway.QueryCall
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryErrorCategory
import me.ahoo.wow.query.gateway.QueryExecutionException
import me.ahoo.wow.query.gateway.QueryPurpose
import me.ahoo.wow.query.gateway.QueryResourceScope
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.gateway.QueryTrustedContext
import me.ahoo.wow.query.gateway.QueryTrustedContextRequest
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.webflux.route.command.getOwnerId
import me.ahoo.wow.webflux.route.command.getSpaceId
import me.ahoo.wow.webflux.route.command.getTenantId
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * Converts an authenticated application request context into Query Gateway authority.
 *
 * Implementations must derive the principal and grants from an authenticated source. Path variables and request
 * headers are selectors already captured in [QueryWebAuthorityRequest.call]; they are never authority evidence.
 */
@ExperimentalQueryGatewayApi
fun interface QueryWebAuthorityResolver {
    /** Empty and error signals are fail-closed by the Query Gateway. */
    fun resolve(request: QueryWebAuthorityRequest): Mono<QueryAuthority>
}

@ExperimentalQueryGatewayApi
data class QueryWebAuthorityRequest(
    val call: QueryCall,
    val request: ServerRequest,
)

/** Resolves the compatibility facade call and authority from the same typed WebFlux transport marker. */
@ExperimentalQueryGatewayApi
class QueryWebTransportResolvers(
    private val webAuthorityResolver: QueryWebAuthorityResolver,
) : me.ahoo.wow.query.gateway.QueryTrustedContextResolver {
    override fun resolve(request: QueryTrustedContextRequest): Mono<QueryTrustedContext> =
        Mono.deferContextual { context ->
            val marker = context.queryTransportMarker() ?: return@deferContextual Mono.empty()
            if (marker.call.target != request.callRequest.target || marker.queryType != request.callRequest.queryType) {
                return@deferContextual Mono.error(transportMismatch("QUERY_TRANSPORT_CALL_MISMATCH"))
            }
            Mono.defer { webAuthorityResolver.resolve(QueryWebAuthorityRequest(marker.call, marker.request)) }
                .onErrorMap(::authorityResolutionFailed)
                .switchIfEmpty(Mono.error(authorityRequired()))
                .map { authority -> QueryTrustedContext(marker.call, authority) }
        }
}

internal fun <T : Any> Mono<T>.writeQueryWebTransport(
    request: ServerRequest,
    aggregateMetadata: AggregateMetadata<*, *>,
    documentKind: QueryDocumentKind?,
    queryType: QueryType,
    effectiveTenantId: String? = null,
): Mono<T> =
    documentKind?.let { declaredKind ->
        contextWrite { context ->
            context.put(
                QUERY_TRANSPORT_MARKER_KEY,
                request.toMarker(aggregateMetadata, declaredKind, queryType, effectiveTenantId),
            )
        }
    } ?: this

internal fun <T : Any> Flux<T>.writeQueryWebTransport(
    request: ServerRequest,
    aggregateMetadata: AggregateMetadata<*, *>,
    documentKind: QueryDocumentKind?,
    queryType: QueryType,
    effectiveTenantId: String? = null,
): Flux<T> =
    documentKind?.let { declaredKind ->
        contextWrite { context ->
            context.put(
                QUERY_TRANSPORT_MARKER_KEY,
                request.toMarker(aggregateMetadata, declaredKind, queryType, effectiveTenantId),
            )
        }
    } ?: this

internal fun QueryHandler<*>.queryDocumentKind(): QueryDocumentKind? = when (this) {
    is SnapshotQueryHandler -> QueryDocumentKind.SNAPSHOT
    is EventStreamQueryHandler -> QueryDocumentKind.EVENT_STREAM
    else -> null
}

private data class QueryTransportMarker(
    val call: QueryCall,
    val queryType: QueryType,
    val request: ServerRequest,
)

private fun ServerRequest.toMarker(
    aggregateMetadata: AggregateMetadata<*, *>,
    documentKind: QueryDocumentKind,
    queryType: QueryType,
    effectiveTenantId: String?,
): QueryTransportMarker = QueryTransportMarker(
    call = QueryCall(
        target = QueryTarget(aggregateMetadata, documentKind),
        purpose = WEB_QUERY_PURPOSE,
        resourceScope = QueryResourceScope(
            tenantId = effectiveTenantId ?: getTenantId(aggregateMetadata),
            ownerId = getOwnerId(),
            spaceId = getSpaceId(),
        ),
    ),
    queryType = queryType,
    request = this,
)

private fun ContextView.queryTransportMarker(): QueryTransportMarker? =
    getOrDefault(QUERY_TRANSPORT_MARKER_KEY, null)

private fun transportMismatch(code: String): QueryExecutionException = QueryExecutionException(
    category = QueryErrorCategory.ACCESS_DENIED,
    path = "$.executionContext.transport",
    code = code,
)

private fun authorityRequired(): QueryExecutionException = QueryExecutionException(
    category = QueryErrorCategory.ACCESS_DENIED,
    path = "$.executionContext.authority",
    code = "AUTHORITY_REQUIRED",
)

private fun authorityResolutionFailed(cause: Throwable): QueryExecutionException = QueryExecutionException(
    category = QueryErrorCategory.ACCESS_DENIED,
    path = "$.executionContext.authority",
    code = "AUTHORITY_RESOLUTION_FAILED",
    cause = cause,
)

private val WEB_QUERY_PURPOSE = QueryPurpose("interactive-query")
private const val QUERY_TRANSPORT_MARKER_KEY = "me.ahoo.wow.query.web.transport"
