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
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Copies each `Command-Header-<key>` request header into the command message header as `<key>`.
 *
 * The prefix matches case-insensitively, since HTTP/2 sends header names in lower case. A key the framework reserves
 * ([ReservedCommandHeaderKeys]: the operator, wait, local-first, trace and compensation keys …) is rejected with an
 * [IllegalArgumentException] (`IllegalArgument`, 400) instead of being copied.
 */
object CommandRequestExtendHeaderAppender : CommandRequestHeaderAppender {
    private const val PREFIX = CommandComponent.Header.COMMAND_HEADER_X_PREFIX

    override fun append(request: ServerRequest, header: Header) {
        val extendedHeaders = request.headers().asHttpHeaders().headerSet()
            .filter { (key, _) -> key.startsWith(PREFIX, ignoreCase = true) }
            .associate { (key, value) ->
                val headerKey = key.substring(PREFIX.length)
                require(!ReservedCommandHeaderKeys.isReserved(headerKey)) {
                    "Command header [$headerKey] is reserved by the framework and cannot be set through [$key]."
                }
                headerKey to value.firstOrNull<String>().orEmpty()
            }
        if (extendedHeaders.isEmpty()) {
            return
        }
        header.with(extendedHeaders)
    }
}
