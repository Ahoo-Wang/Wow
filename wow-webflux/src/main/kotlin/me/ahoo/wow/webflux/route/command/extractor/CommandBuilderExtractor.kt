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

package me.ahoo.wow.webflux.route.command.extractor

import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono

fun interface CommandBuilderExtractor {
    fun extract(
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        commandBody: Any,
        request: ServerRequest
    ): Mono<CommandBuilder>
}
