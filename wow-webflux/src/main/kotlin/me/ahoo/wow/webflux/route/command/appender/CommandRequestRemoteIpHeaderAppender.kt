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

package me.ahoo.wow.webflux.route.command.appender

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withRemoteIp
import org.springframework.web.reactive.function.server.ServerRequest
import kotlin.jvm.optionals.getOrNull

/**
 * Appends the client address as `remote_ip`: the first non-blank entry of the first `X-Forwarded-For` header, taken
 * as is, otherwise the remote address of the connection.
 *
 * The remote address is written as its address literal (`hostString`), never resolved by a reverse DNS lookup: the
 * appender runs on the event loop, where a lookup blocks every request on that loop. Since 9.2.3; earlier versions
 * wrote the reverse-resolved host name.
 */
object CommandRequestRemoteIpHeaderAppender : CommandRequestHeaderAppender {
    const val X_FORWARDED_FOR = "X-Forwarded-For"
    const val DELIMITER = ','

    override fun append(request: ServerRequest, header: Header) {
        resolveRemoteIp(request)?.let {
            header.withRemoteIp(it)
        }
    }

    private fun resolveRemoteIp(request: ServerRequest): String? =
        request.headers().firstHeader(X_FORWARDED_FOR)
            ?.splitToSequence(DELIMITER)
            ?.firstOrNull { it.isNotBlank() }
            ?: request.remoteAddress().getOrNull()?.hostString
}
