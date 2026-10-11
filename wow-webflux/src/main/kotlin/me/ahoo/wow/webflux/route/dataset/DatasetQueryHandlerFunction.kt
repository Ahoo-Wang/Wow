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

package me.ahoo.wow.webflux.route.dataset

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.QueryScopeProvenance
import me.ahoo.wow.query.dataset.DatasetQueryGateway
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.rest.RouteVariables
import me.ahoo.wow.rest.WowHeaders
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.AGGREGATION_QUERY_EXTRACTOR
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.CURSOR_QUERY_EXTRACTOR
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.FILTER_EXPRESSION_EXTRACTOR
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.LIST_QUERY_EXTRACTOR
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.PAGED_QUERY_EXTRACTOR
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.SINGLE_QUERY_EXTRACTOR
import me.ahoo.wow.webflux.route.query.QueryBodyExtractor.Companion.STRICT_FILTER_EXPRESSION_EXTRACTOR
import me.ahoo.wow.webflux.route.query.QueryHandlerSupport
import me.ahoo.wow.webflux.route.query.QuerySchemaHandlerFunction
import me.ahoo.wow.webflux.route.query.ScopeContributor
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * The caller scope of a dataset route, mapped by admission onto the dataset's own tenant, owner and space columns.
 * Only an [authenticated][QueryScopeProvenance.AUTHENTICATED] part is a security boundary.
 */
fun interface DatasetQueryRequestScope {
    fun resolve(dataset: QueryDataset, request: ServerRequest): QueryScope
}

/**
 * Reads the tenant from the `{tenantId}` path variable, else `Command-Tenant-Id`; the owner from `Command-Owner-Id` and
 * the space from `Wow-Space-Id`, each only when the dataset has that column. Every value comes from the request, so
 * each is [declared][QueryScopeProvenance.DECLARED]; override [provenance] when a trusted component (an authenticating
 * gateway that owns these headers, say) vouches for them.
 */
open class DefaultDatasetQueryRequestScope : DatasetQueryRequestScope {
    protected open fun provenance(dataset: QueryDataset, request: ServerRequest): QueryScopeProvenance =
        QueryScopeProvenance.DECLARED

    override fun resolve(dataset: QueryDataset, request: ServerRequest): QueryScope {
        val tenant = request.pathVariables()[RouteVariables.TENANT_ID] ?: request.header(CommandHeaders.TENANT_ID)
        val parts = listOfNotNull(
            tenant?.let(::TenantIdFilter),
            dataset.scope.owner?.let { request.header(CommandHeaders.OWNER_ID)?.let(::OwnerIdFilter) },
            dataset.scope.space?.let { request.header(WowHeaders.SPACE_ID)?.let(::SpaceIdFilter) },
        )
        val scope = parts.fold(MatchAllFilter as FilterExpression) { scope, part -> scope.appendFilter(part) }
        return when (provenance(dataset, request)) {
            QueryScopeProvenance.AUTHENTICATED -> QueryScope(authenticated = scope)
            QueryScopeProvenance.DECLARED -> QueryScope(declared = scope)
        }
    }

    private fun ServerRequest.header(name: String): String? =
        headers().firstHeader(name)?.takeUnless(String::isBlank)

    companion object INSTANCE : DefaultDatasetQueryRequestScope()
}

/**
 * The caller scope of the dataset routes: the host's [identity] scope followed by every [ScopeContributor]'s dataset
 * dimension, each half appended to the same half, as [me.ahoo.wow.webflux.route.query.CompositeQueryRequestScope]
 * does for the aggregate routes. Since 9.9.0.
 */
class CompositeDatasetQueryRequestScope(
    val identity: DatasetQueryRequestScope,
    val contributors: List<ScopeContributor>,
) : DatasetQueryRequestScope {
    override fun resolve(dataset: QueryDataset, request: ServerRequest): QueryScope =
        contributors.fold(identity.resolve(dataset, request)) { scope, contributor ->
            scope.and(contributor.contribute(dataset, request))
        }

    companion object {
        /** [identity] with [contributors] appended; [identity] itself when there are none. */
        fun of(identity: DatasetQueryRequestScope, contributors: List<ScopeContributor>): DatasetQueryRequestScope =
            if (contributors.isEmpty()) identity else CompositeDatasetQueryRequestScope(identity, contributors)
    }
}

private fun QueryScope.and(other: QueryScope): QueryScope = if (other == QueryScope.NONE) {
    this
} else {
    QueryScope(authenticated = authenticated.and(other.authenticated), declared = declared.and(other.declared))
}

private fun FilterExpression.and(other: FilterExpression): FilterExpression =
    if (other == MatchAllFilter) this else appendFilter(other)

/** The query operations a dataset route serves. */
enum class DatasetQueryOperation { SCHEMA, COUNT, AGGREGATION, LIST, PAGED, CURSOR, SINGLE }

/**
 * One dataset query route: it finds the dataset named by `{context}` and `{dataset}` (404 when this instance serves no
 * such dataset) and runs [operation] through the same pipeline as an aggregate's query routes: the HTTP entry, the
 * caller scope, the HTTP guard and the capability descriptor's ETag.
 */
class DatasetQueryHandlerFunction(
    private val operation: DatasetQueryOperation,
    private val gateways: DatasetQueryGateways,
    private val requestScope: DatasetQueryRequestScope,
    private val exceptionHandler: RequestExceptionHandler,
    private val guard: HttpQueryGuard = HttpQueryGuard(),
) : HandlerFunction<ServerResponse> {
    // Keyed by name and checked against the gateway: a dataset replaced by another declaration has a new gateway.
    private val handlers =
        ConcurrentHashMap<MaterializedNamedAggregate, Pair<DatasetQueryGateway, HandlerFunction<ServerResponse>>>()

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val name = MaterializedNamedAggregate(
            request.pathVariable(RouteVariables.CONTEXT),
            request.pathVariable(RouteVariables.DATASET),
        )
        val gateway = gateways[name] ?: return exceptionHandler.handle(
            request,
            NotFoundResourceException("Dataset [$name] is not served here."),
        )
        val handler = handlers.compute(name) { _, cached ->
            cached?.takeIf { it.first === gateway } ?: (gateway to handler(gateway))
        }.let(::checkNotNull).second
        return handler.handle(request)
    }

    private fun handler(gateway: DatasetQueryGateway): HandlerFunction<ServerResponse> {
        if (operation == DatasetQueryOperation.SCHEMA) {
            return QuerySchemaHandlerFunction(
                gateway,
                exceptionHandler,
                guard
            )
        }
        val support =
            QueryHandlerSupport({ requestScope.resolve(gateway.dataset, it) }, exceptionHandler, guard.of(gateway))
        val countExtractor = if (guard.strictCountFilter) STRICT_FILTER_EXPRESSION_EXTRACTOR else FILTER_EXPRESSION_EXTRACTOR
        return HandlerFunction { request ->
            when (operation) {
                DatasetQueryOperation.COUNT -> support.mono(request, countExtractor) { gateway.count(it) }
                DatasetQueryOperation.AGGREGATION ->
                    support.flux(request, AGGREGATION_QUERY_EXTRACTOR) { gateway.aggregate(it) }
                DatasetQueryOperation.LIST ->
                    support.flux(request, LIST_QUERY_EXTRACTOR, prepare = support.guard::applyListDefault) {
                        gateway.dynamicList(it)
                    }
                DatasetQueryOperation.PAGED -> support.mono(request, PAGED_QUERY_EXTRACTOR) { gateway.dynamicPaged(it) }
                DatasetQueryOperation.CURSOR -> support.mono(
                    request,
                    CURSOR_QUERY_EXTRACTOR
                ) { gateway.dynamicCursor(it) }
                DatasetQueryOperation.SINGLE ->
                    support.mono(request, SINGLE_QUERY_EXTRACTOR, notFoundIfEmpty = true) { gateway.dynamicSingle(it) }
                DatasetQueryOperation.SCHEMA -> error("unreachable")
            }
        }
    }
}

/** Creates the handler of one dataset route, identified by [handlerKey]. */
class DatasetQueryHandlerFunctionFactory(
    override val handlerKey: String,
    private val operation: DatasetQueryOperation,
    private val gateways: DatasetQueryGateways,
    private val requestScope: DatasetQueryRequestScope,
    private val exceptionHandler: RequestExceptionHandler,
    private val guard: HttpQueryGuard = HttpQueryGuard(),
) : HttpRouteHandlerFunctionFactory {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> =
        DatasetQueryHandlerFunction(operation, gateways, requestScope, exceptionHandler, guard)
}
