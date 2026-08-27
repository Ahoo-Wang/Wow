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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata

internal fun HttpRouteHandlerMetadata.requireAggregateHandlerMetadata(
    handlerKey: String
): HttpRouteHandlerMetadata.Aggregate {
    return this as? HttpRouteHandlerMetadata.Aggregate
        ?: invalidHandlerMetadata(handlerKey, HttpRouteHandlerMetadata.Aggregate::class.java)
}

internal fun HttpRouteHandlerMetadata.requireCommandHandlerMetadata(
    handlerKey: String
): HttpRouteHandlerMetadata.Command {
    return this as? HttpRouteHandlerMetadata.Command
        ?: invalidHandlerMetadata(handlerKey, HttpRouteHandlerMetadata.Command::class.java)
}

internal fun HttpRouteHandlerMetadata.requireNoHandlerMetadata(
    handlerKey: String
): HttpRouteHandlerMetadata.None {
    return this as? HttpRouteHandlerMetadata.None
        ?: invalidHandlerMetadata(handlerKey, HttpRouteHandlerMetadata.None::class.java)
}

private fun invalidHandlerMetadata(
    handlerKey: String,
    expected: Class<out HttpRouteHandlerMetadata>
): Nothing {
    error(
        "HttpRouteHandlerMetadata mismatch - " +
            "handlerKey:[$handlerKey], expected:[${expected.name}]."
    )
}
