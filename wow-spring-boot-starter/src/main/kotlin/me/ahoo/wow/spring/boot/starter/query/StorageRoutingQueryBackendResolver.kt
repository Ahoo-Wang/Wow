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
package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendRouteSnapshot
import reactor.core.publisher.Mono

internal class StorageRoutingQueryBackendResolver private constructor(
    routeSnapshot: QueryBackendRouteSnapshot,
) : QueryBackendResolver {
    private val routes = routeSnapshot

    @JvmSynthetic
    override fun resolve(target: QueryTarget): Mono<ResolvedQueryBackend> = backendNotReady()

    @JvmSynthetic
    override fun resolve(context: QueryBackendResolutionContext): Mono<ResolvedQueryBackend> = Mono.defer {
        routes.selection(context.target).binding
            ?.let { binding -> bind(binding, context) }
            ?: backendNotReady()
    }

    /**
     * A storage factory must bind synchronously and without I/O. Readiness starts only after this method validates
     * the bound descriptor against the secured capability expression.
     */
    private fun bind(
        binding: QueryBackendBinding,
        context: QueryBackendResolutionContext,
    ): Mono<ResolvedQueryBackend> = try {
        val backend = binding.backendFactory.bind(context)
        validateCapabilities(backend.descriptor.capabilities, backend.descriptor.backendId, context.securedExpression)
        ResolvedQueryBackend.resolve(
            backend,
            QueryBackendRouteIdentity("${binding.name}:${context.target.documentKind.name}"),
        )
    } catch (error: QueryException) {
        Mono.error(error)
    } catch (_: Exception) {
        backendNotReady()
    }

    private fun validateCapabilities(
        supportedCapabilities: Set<QueryCapabilityId>,
        backendId: String,
        expression: QueryExpression,
    ) {
        val requestedCapabilities = LinkedHashSet<QueryCapabilityId>()
        val nativeBackendIds = LinkedHashSet<String>()
        val pending = ArrayDeque<QueryExpression>()
        pending += expression
        while (pending.isNotEmpty()) {
            when (val current = pending.removeLast()) {
                is FullTextExpression -> requestedCapabilities += current.capabilityId
                is NativeExpression -> {
                    requestedCapabilities += current.capabilityId
                    nativeBackendIds += current.backendId
                }

                is LogicalExpression -> current.operands.forEach(pending::addLast)
                is PortableLogicalExpression -> current.operands.forEach(pending::addLast)
                is ElementMatchExpression -> pending += current.predicate
                MatchAll,
                MatchNone,
                is PredicateExpression -> Unit
            }
        }
        if (!supportedCapabilities.containsAll(requestedCapabilities) || nativeBackendIds.any { it != backendId }) {
            throw unsupportedCapability()
        }
    }

    private fun unsupportedCapability(): QueryException = QueryException(
        QueryErrorCode.UNSUPPORTED_CAPABILITY,
        QueryStage.PLANNING,
        QueryErrorReason.CAPABILITY_DENIED,
    )

    private fun backendNotReady(): Mono<ResolvedQueryBackend> = Mono.error(
        QueryException(
            QueryErrorCode.BACKEND_NOT_READY,
            QueryStage.BACKEND_RESOLUTION,
            QueryErrorReason.BACKEND_UNAVAILABLE,
        ),
    )

    companion object {
        @JvmSynthetic
        operator fun invoke(routeSnapshot: QueryBackendRouteSnapshot): StorageRoutingQueryBackendResolver =
            StorageRoutingQueryBackendResolver(routeSnapshot)
    }
}
