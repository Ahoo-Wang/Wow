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

package me.ahoo.wow.viewstore.server.system

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.domain.ViewStoreRejections
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/**
 * The system views route of the 9.x starter, on V10 (roadmap 7.2a), read-only:
 *
 * - `GET /view-store/system-views?definitionId=`: the system views of the caller's tenant and application, configured
 *   and stored ([StoredSystemViews]); all of them without a definition;
 * - `GET /view-store/system-views/{id}`: one of them, a `view-store.not-found` problem otherwise.
 *
 * The caller is the service's [CallerResolver]'s (401 `wow.auth.unauthenticated` when it accepts none); the
 * application is the caller's, the one its resolver authenticated ([me.ahoo.wow.api.caller.Caller.app], decision D232;
 * 400 `view-store.app-required` without it). The route sits under the context's path,
 * so the service orders it before Wow's routes, whose catch-all answers any other path there with a 404. A failure is
 * a problem as on Wow's routes (review R2.3-8): a store that is unavailable is `wow.storage.unavailable` with
 * `Retry-After`, anything else a 500 that says nothing of its cause, which is logged.
 */
class SystemViewRoutes(private val views: StoredSystemViews, private val callers: CallerResolver) {
    fun routes(): RouterFunction<ServerResponse> = coRouter {
        GET(SYSTEM_VIEWS) { request -> answering(request) { tenantId, appId -> list(request, tenantId, appId) } }
        GET(
            "$SYSTEM_VIEWS/{$ID}"
        ) { request -> answering(request) { tenantId, appId -> one(request, tenantId, appId) } }
    }

    private suspend fun list(request: ServerRequest, tenantId: String, appId: String): ServerResponse {
        val definitionId = request.queryParam(DEFINITION_ID).orElse(null)?.ifBlank { null }
        return json(views.systemViews(tenantId, appId, definitionId))
    }

    private suspend fun one(request: ServerRequest, tenantId: String, appId: String): ServerResponse {
        val id = request.pathVariable(ID)
        val view = views.systemView(tenantId, appId, id)
            ?: return problem(HttpStatus.NOT_FOUND, ViewStoreRejections.NOT_FOUND, "System view [$id] is not found.")
        return json(view)
    }

    @Suppress("TooGenericExceptionCaught") // Whatever a route throws is answered as a problem, not a bare 500.
    private suspend fun answering(
        request: ServerRequest,
        answer: suspend (tenantId: String, appId: String) -> ServerResponse,
    ): ServerResponse = try {
        authenticated(request, answer)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val problem = Problem.ofFailure(failure)
        if (problem.retryAfter == null) {
            logger.error(failure) { "A system views request failed with an unexpected error." }
        } else {
            logger.warn(failure) { "A system views request failed: a store is unavailable." }
        }
        problem.respond()
    }

    private suspend fun authenticated(
        request: ServerRequest,
        answer: suspend (tenantId: String, appId: String) -> ServerResponse,
    ): ServerResponse {
        val caller = callers.resolve(request)
            ?: return problem(HttpStatus.UNAUTHORIZED, UNAUTHENTICATED, "The request carries no credentials accepted here.")
        val appId = caller.app?.trim() ?: return problem(
            HttpStatus.BAD_REQUEST,
            ViewStoreRejections.APP_REQUIRED,
            "The request carries no authenticated application (${ViewStoreContext.APP_ID_HEADER}).",
        )
        return answer(caller.tenantId, appId)
    }

    private suspend fun json(body: Any): ServerResponse =
        ServerResponse.ok().contentType(
            MediaType.APPLICATION_JSON
        ).bodyValueAndAwait(WowJson.mapper.writeValueAsBytes(body))

    private suspend fun problem(status: HttpStatus, code: String, detail: String): ServerResponse =
        Problem(status.value(), status.reasonPhrase, detail, code).respond()

    companion object {
        const val SYSTEM_VIEWS: String = "/${ViewStoreContext.NAME}/system-views"
        const val ID: String = "id"
        const val DEFINITION_ID: String = "definitionId"
        private const val UNAUTHENTICATED: String = "wow.auth.unauthenticated"
        private val logger = KotlinLogging.logger {}
    }
}
