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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAdmissionContext
import me.ahoo.wow.query.invocation.QueryAuthorityProvider
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class WebFluxQueryAdmission(
    private val authorityResolver: WebFluxQueryAuthorityResolver,
    private val fallbackAuthorityProvider: QueryAuthorityProvider
) : QueryAdmission {

    override fun admit(context: QueryAdmissionContext): Mono<QueryInvocationScope> = Mono.deferContextual { reactor ->
        val request = reactor.getOrEmpty<ServerRequest>(REQUEST_CONTEXT_KEY).orElse(null)
        val authority = if (request == null) {
            fallbackAuthorityProvider.getAuthority(context)
        } else {
            request.principal()
                .flatMap { principal ->
                    authorityResolver.resolve(principal).switchIfEmpty(Mono.error(authorityResolutionFailure()))
                }
                .switchIfEmpty(Mono.just(ANONYMOUS_AUTHORITY))
        }
        authority.map { verified ->
            QueryInvocationScope(
                trustedAuthority = verified,
                requestedScope = context.request.requestedScope,
                correlationId = context.correlationId
            )
        }
    }

    fun <T : Any> bind(request: ServerRequest, publisher: Mono<T>): Mono<T> = publisher.contextWrite {
        it.put(REQUEST_CONTEXT_KEY, request)
    }

    fun <T : Any> bind(request: ServerRequest, publisher: Flux<T>): Flux<T> = publisher.contextWrite {
        it.put(REQUEST_CONTEXT_KEY, request)
    }

    private fun authorityResolutionFailure(): QueryException = QueryException(
        QueryErrorCode.POLICY_FAILURE,
        QueryStage.ADMISSION,
        QueryErrorReason.POLICY_EVALUATION_FAILED
    )

    private companion object {
        val REQUEST_CONTEXT_KEY = WebFluxQueryAdmission::class.java
        val ANONYMOUS_AUTHORITY = QueryAuthorityView(null, null, null, emptySet(), emptySet())
    }
}
