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

object CommandRequestRemoteIpHeaderAppender : CommandRequestHeaderAppender {
    const val X_FORWARDED_FOR = "X-Forwarded-For"
    const val DELIMITER = ','

    override fun append(request: ServerRequest, header: Header) {
        resolveRemoteIp(request)?.let {
            header.withRemoteIp(it)
        }
    }

    private fun getRemoteIp(request: ServerRequest): String? {
        return request.remoteAddress().getOrNull()?.hostName
    }

    private fun resolveRemoteIp(request: ServerRequest): String? {
        val xForwardedHeaderValue = request.headers().firstHeader(X_FORWARDED_FOR)
        if (xForwardedHeaderValue.isNullOrBlank()) {
            return getRemoteIp(request)
        }

        val xForwardedValues = xForwardedHeaderValue
            .split(DELIMITER)
            .filter { it.isNotBlank() }
            .reversed()
        if (xForwardedValues.isEmpty()) {
            return getRemoteIp(request)
        }
        val index = xForwardedValues.size.coerceAtMost(Int.MAX_VALUE) - 1
        return xForwardedValues[index]
    }
}
