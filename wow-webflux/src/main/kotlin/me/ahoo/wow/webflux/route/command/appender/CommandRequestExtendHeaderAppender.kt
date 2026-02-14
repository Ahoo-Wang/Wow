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

object CommandRequestExtendHeaderAppender : CommandRequestHeaderAppender {
    override fun append(request: ServerRequest, header: Header) {
        val extendedHeaders = request.headers().asHttpHeaders().headerSet()
            .filter { (key, _) -> key.startsWith(CommandComponent.Header.COMMAND_HEADER_X_PREFIX) }
            .map { (key, value) ->
                key.substring(CommandComponent.Header.COMMAND_HEADER_X_PREFIX.length) to value.firstOrNull<String>().orEmpty()
            }.toMap()
        if (extendedHeaders.isEmpty()) {
            return
        }
        header.with(extendedHeaders)
    }
}
