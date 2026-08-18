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

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import reactor.core.Exceptions
import reactor.core.publisher.Mono

class ResolvedQueryBackend private constructor(
    val backend: QueryBackend,
    val descriptor: QueryBackendDescriptor,
    val routeIdentity: QueryBackendRouteIdentity,
    val readinessSnapshot: QueryBackendReadiness
) {
    override fun toString(): String =
        "ResolvedQueryBackend(backendId=${descriptor.backendId}, routeIdentity=<redacted>, " +
            "readiness=${readinessSnapshot.safeKind()})"

    private fun QueryBackendReadiness.safeKind(): String = when (this) {
        QueryBackendReadiness.Ready -> "READY"
        is QueryBackendReadiness.NotReady -> "NOT_READY:${reason.name}"
    }

    companion object {
        @JvmStatic
        fun resolve(
            backend: QueryBackend,
            routeIdentity: QueryBackendRouteIdentity,
        ): Mono<ResolvedQueryBackend> = Mono.deferContextual { subscriberContext ->
            val context = subscriberContext.getOrEmpty<QueryBackendResolutionContext>(
                QueryBackendResolutionContext::class.java,
            ).orElse(null)
            resolve(backend, routeIdentity) { descriptor ->
                context?.let { validateBackendCompatibility(it, descriptor) }
            }
        }

        @JvmStatic
        fun resolve(
            backend: QueryBackend,
            routeIdentity: QueryBackendRouteIdentity,
            context: QueryBackendResolutionContext,
        ): Mono<ResolvedQueryBackend> = resolve(backend, routeIdentity) { descriptor ->
            validateBackendCompatibility(context, descriptor)
        }

        private fun resolve(
            backend: QueryBackend,
            routeIdentity: QueryBackendRouteIdentity,
            preflight: (QueryBackendDescriptor) -> Unit,
        ): Mono<ResolvedQueryBackend> = Mono.defer {
            val descriptorSnapshot = backend.descriptor
            preflight(descriptorSnapshot)
            backend.readiness()
                .switchIfEmpty(Mono.error(backendUnavailable()))
                .map { readiness ->
                    ResolvedQueryBackend(
                        backend = backend,
                        descriptor = descriptorSnapshot,
                        routeIdentity = routeIdentity,
                        readinessSnapshot = readiness
                    )
                }
                .onErrorMap { error ->
                    Exceptions.throwIfFatal(error)
                    backendUnavailable()
                }
        }
            .onErrorMap { error ->
                Exceptions.throwIfFatal(error)
                if (error is QueryException) error else backendUnavailable()
            }

        private fun backendUnavailable(): QueryException = QueryException(
            QueryErrorCode.BACKEND_NOT_READY,
            QueryStage.BACKEND_RESOLUTION,
            QueryErrorReason.BACKEND_UNAVAILABLE
        )
    }
}
