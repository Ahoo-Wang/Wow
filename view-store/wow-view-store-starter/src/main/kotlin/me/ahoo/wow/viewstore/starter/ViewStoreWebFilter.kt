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

package me.ahoo.wow.viewstore.starter

import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Two rules of the view store's routes that sit before Wow's command routes:
 * - the server generates every aggregate id, so a `Command-Aggregate-Id` a caller sends is dropped;
 * - a system view is read-only, so a write addressed to one is refused with [ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY]
 *   instead of reading as a view that does not exist.
 *
 * Requests outside the view store's paths pass untouched.
 */
class ViewStoreWebFilter(
    private val paths: ViewStorePaths,
    private val systemViewProvider: SystemViewProvider,
) : WebFilter {
    companion object {
        private val WRITE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.pathWithinApplication().value()
        if (!paths.isViewStorePath(path)) {
            return chain.filter(exchange)
        }
        val filtered = exchange.withoutAggregateId()
        val request = filtered.request
        val target = paths.viewTarget(path)
        val appId = request.headers.getFirst(ViewStoreService.APP_ID_HEADER)
        if (request.method !in WRITE_METHODS || target == null || appId.isNullOrBlank()) {
            return chain.filter(filtered)
        }
        return systemViewProvider.systemViews(target.tenantId, appId)
            .any { it.id == target.viewId }
            .flatMap { systemView ->
                if (systemView) filtered.readOnly(target.viewId) else chain.filter(filtered)
            }
    }

    private fun ServerWebExchange.withoutAggregateId(): ServerWebExchange {
        if (!request.headers.containsHeader(CommandComponent.Header.AGGREGATE_ID)) {
            return this
        }
        return mutate().request { builder ->
            builder.headers { it.remove(CommandComponent.Header.AGGREGATE_ID) }
        }.build()
    }

    private fun ServerWebExchange.readOnly(viewId: String): Mono<Void> {
        val errorInfo = ViewStoreException(
            ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY,
            "System view [$viewId] is read-only."
        ).toErrorInfo()
        response.statusCode = errorInfo.toHttpStatus()
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.set(ERROR_CODE, errorInfo.errorCode)
        val body = response.bufferFactory().wrap(errorInfo.toJsonString().toByteArray(Charsets.UTF_8))
        return response.writeWith(Mono.just(body))
    }
}
