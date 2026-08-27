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

import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.RequestPredicates

internal class HttpRoutePredicateFactory {
    fun create(contract: HttpRouteContract): RequestPredicate {
        val acceptMediaTypes = contract.runtimeAcceptMediaTypes()
        val httpMethod = HttpMethod.valueOf(contract.method)
        return RequestPredicates.path(contract.path)
            .and(RequestPredicates.method(httpMethod))
            .and(RequestPredicates.accept(*acceptMediaTypes))
    }
}

private fun HttpRouteContract.runtimeAcceptMediaTypes(): Array<MediaType> =
    if (handlerKey in HANDLER_NEGOTIATED_ACCEPT_KEYS) {
        arrayOf(MediaType.ALL)
    } else {
        MediaType.parseMediaTypes(accept).toTypedArray()
    }

private val HANDLER_NEGOTIATED_ACCEPT_KEYS = setOf(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)
