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

package me.ahoo.wow.analytics.catalog.server

import kotlinx.coroutines.CancellationException
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.spi.json.WowJson
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import tools.jackson.core.JacksonException

/**
 * The routes of the datasets' reference actions ([DatasetReferenceActions]) on the public port, as the caller
 * [callers] resolves (401 without one):
 *
 * - `GET /analytics-catalog/datasets/{dataset}/reference-actions`: the commands each REFERENCE field of a served
 *   dataset (`context.name`) offers on its rows, 404 for a dataset not served;
 * - `POST /analytics-catalog/datasets/{dataset}/rows/{field}/{id}/{command}`: one of them sent, its body the
 *   command's JSON, to the aggregate [id] of the row's [field] refers to, answered with the command's result.
 */
fun referenceActionRoutes(actions: DatasetReferenceActions, callers: CallerResolver): RouterFunction<ServerResponse> =
    coRouter {
        GET(ACTIONS) { request ->
            answering(request, callers) { caller ->
                val dataset = request.pathVariable(DATASET)
                val found = actions.actionsOf(dataset, caller)
                    ?: throw ReferenceActionRefusal(
                        Problem.ofStatus(HttpStatus.NOT_FOUND, "No dataset $dataset is served."),
                    )
                json(found)
            }
        }
        POST(RELAY) { request ->
            answering(request, callers) { caller ->
                val bytes = request.awaitBodyOrNull<ByteArray>()
                val body = try {
                    if (bytes == null || bytes.isEmpty()) {
                        WowJson.mapper.createObjectNode()
                    } else {
                        WowJson.mapper.readTree(
                            bytes
                        )
                    }
                } catch (malformed: JacksonException) {
                    throw ReferenceActionRefusal(
                        Problem(400, "Bad Request", malformed.originalMessage, FrameworkErrors.COMMAND_MALFORMED),
                    )
                }
                json(
                    actions.send(
                        request.pathVariable(DATASET),
                        request.pathVariable(FIELD),
                        request.pathVariable(ID),
                        request.pathVariable(COMMAND),
                        body,
                        caller,
                    ),
                )
            }
        }
    }

private const val DATASET = "dataset"
private const val FIELD = "field"
private const val ID = "id"
private const val COMMAND = "command"

/** Where a served dataset's reference actions are listed. */
const val ACTIONS: String = "/analytics-catalog/datasets/{$DATASET}/reference-actions"

/** Where one is sent, for a row. */
const val RELAY: String = "/analytics-catalog/datasets/{$DATASET}/rows/{$FIELD}/{$ID}/{$COMMAND}"

private suspend fun json(value: Any): ServerResponse =
    ServerResponse.ok().contentType(
        MediaType.APPLICATION_JSON
    ).bodyValueAndAwait(WowJson.mapper.writeValueAsBytes(value))

@Suppress("TooGenericExceptionCaught") // Whatever fails is answered as a problem, never Spring's error page.
private suspend fun answering(
    request: ServerRequest,
    callers: CallerResolver,
    answer: suspend (Caller) -> ServerResponse,
): ServerResponse = try {
    val caller = callers.resolve(request)
        ?: throw ReferenceActionRefusal(
            Problem(401, "Unauthorized", "Name the caller to act on a row.", FrameworkErrors.AUTH_UNAUTHENTICATED),
        )
    answer(caller)
} catch (refusal: ReferenceActionRefusal) {
    refusal.problem.respond()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Problem.ofFailure(failure).respond()
}
