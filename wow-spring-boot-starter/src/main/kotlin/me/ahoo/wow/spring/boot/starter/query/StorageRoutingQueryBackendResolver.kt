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
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendRouteSnapshot
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendSelection
import reactor.core.publisher.Mono

internal class StorageRoutingQueryBackendResolver(
    routeSnapshot: QueryBackendRouteSnapshot,
) : QueryBackendResolver {
    private val routes = routeSnapshot

    override fun resolve(target: QueryTarget): Mono<ResolvedQueryBackend> = backendNotReady()

    override fun resolve(context: QueryBackendResolutionContext): Mono<ResolvedQueryBackend> = Mono.defer {
        when (val selection = routes.selection(context.target)) {
            QueryBackendSelection.Unavailable -> backendNotReady()
            is QueryBackendSelection.Available -> bind(selection.binding, context)
        }
    }

    private fun bind(
        binding: QueryBackendBinding,
        context: QueryBackendResolutionContext,
    ): Mono<ResolvedQueryBackend> = try {
        ResolvedQueryBackend.resolve(
            binding.backendFactory.bind(context),
            QueryBackendRouteIdentity("${binding.name}:${context.target.documentKind.name}"),
        )
    } catch (_: Throwable) {
        backendNotReady()
    }

    private fun backendNotReady(): Mono<ResolvedQueryBackend> = Mono.error(
        QueryException(
            QueryErrorCode.BACKEND_NOT_READY,
            QueryStage.BACKEND_RESOLUTION,
            QueryErrorReason.BACKEND_UNAVAILABLE,
        ),
    )
}
