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

package me.ahoo.wow.http

import kotlinx.coroutines.reactor.mono
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import org.springframework.http.server.PathContainer
import org.springframework.web.reactive.function.server.HandlerFilterFunction
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.util.TreeSet

/**
 * What the public port refuses of a request outside its body before any route handles it, so that nothing a client
 * sends is read silently as something else, or dropped:
 *
 * - **An unknown `Wow-` header** (400 `wow.request.malformed`, naming it): the prefix is Wow's, so a typo (`Wow-Wiat`)
 *   would wait for the default, or act in the caller's own scope. Known are the request headers of [WowHeaders], the
 *   development identity headers of [DevelopmentHeaders] (read in a development profile alone, sent by any client
 *   with the development credential), and the headers the host lists ([listed]: its request facts and request id
 *   headers). A client that sends a header of a newer Wow is answered 400 by an older node: nodes are upgraded before
 *   the clients. The internal port keeps reading what a peer of the next minor version sends.
 * - **A Wow header given twice** (400 `wow.request.malformed`, naming it): a proxy that appends a line must not choose
 *   the value. `Wow-Scope` alone is a dictionary whose lines are read as one, joined by commas (RFC 8941), as
 *   `If-Match`'s are a list (RFC 9110 §5.3).
 * - **`Wow-Call`** (403 `wow.auth.forbidden`), whatever the request's credential: a service's call is taken on the
 *   internal port alone, on every route.
 * - **Matrix parameters** in the path (`/shop/cart/c-1;x=1/add-item`, 400 `wow.request.malformed`): Wow defines
 *   none, and the router would drop them.
 *
 * Its cost per request is one pass over the request's header names, a case-insensitive comparison of four characters
 * each and, for each `Wow-` one, a case-insensitive lookup in a sorted set of a dozen names (no copy of the name), and
 * one pass over the path's segments, which the router has parsed already.
 */
internal class PublicRequests(
    private val statuses: ErrorStatuses,
    listed: Collection<String> = emptyList(),
) : HandlerFilterFunction<ServerResponse, ServerResponse> {
    private val known: Set<String> = (KNOWN + listed).toCollection(TreeSet(String.CASE_INSENSITIVE_ORDER))

    override fun filter(request: ServerRequest, next: HandlerFunction<ServerResponse>): Mono<ServerResponse> {
        val refusal = refusal(request) ?: return next.handle(request)
        return mono { refusal.respond() }
    }

    /** The problem [request] is refused with, or null when it goes on to its route. */
    fun refusal(request: ServerRequest): Problem? {
        var refusal: Problem? = null
        request.headers().asHttpHeaders().forEach { name, values ->
            if (refusal == null && name.startsWith(PREFIX, ignoreCase = true)) {
                refusal = header(name, values.size)
            }
        }
        return refusal ?: matrix(request)
    }

    /** The problem of the Wow header [name] given in [lines] lines, or null when it is read. */
    private fun header(name: String, lines: Int): Problem? = when {
        name.equals(WowHeaders.CALL, ignoreCase = true) -> statuses.problemOf(
            FrameworkCodes.AUTH_FORBIDDEN,
            "${WowHeaders.CALL} is taken on the internal port alone, never on the public one.",
        )

        name.equals(WowHeaders.REVEAL, ignoreCase = true) -> statuses.problemOf(
            FrameworkCodes.AUTH_FORBIDDEN,
            "${WowHeaders.REVEAL} is a service's read of its peer on the internal port: a read on the public port is " +
                "a caller's, whose DISPLAY values are masked.",
        )

        name !in known -> malformed(
            "$name is not a header of Wow's: the public port takes ${KNOWN.joinToString()} under the prefix $PREFIX.",
        )

        lines > 1 && !name.equals(WowHeaders.SCOPE, ignoreCase = true) ->
            malformed("$name is given $lines times; it is given once.")

        else -> null
    }

    /** The problem of a path segment with matrix parameters, or null when it has none. */
    private fun matrix(request: ServerRequest): Problem? {
        val segment = request.requestPath().pathWithinApplication().elements()
            .firstOrNull { it is PathContainer.PathSegment && !it.parameters().isEmpty() } ?: return null
        return malformed("The path segment '${segment.value()}' has matrix parameters, which no route of Wow takes.")
    }

    private fun malformed(detail: String): Problem = statuses.problemOf(FrameworkCodes.REQUEST_MALFORMED, detail)

    companion object {
        /** The prefix of Wow's headers, which no other header may take on the public port. */
        const val PREFIX: String = "Wow-"

        /** The `Wow-` headers the public port takes, whatever the host lists. */
        val KNOWN: List<String> = listOf(
            WowHeaders.REQUEST_ID,
            WowHeaders.WAIT,
            WowHeaders.WAIT_TIMEOUT,
            WowHeaders.SCOPE,
            WowHeaders.SPACE,
            WowHeaders.SERVICE,
            DevelopmentHeaders.USER,
            DevelopmentHeaders.OWNER,
            DevelopmentHeaders.ROLES,
            DevelopmentHeaders.TENANT,
            DevelopmentHeaders.APP,
        )
    }
}
