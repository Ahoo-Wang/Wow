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

import me.ahoo.wow.exception.NotFoundResourceException
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
import org.springframework.http.server.PathContainer
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * The rules of the view store's routes that sit before Wow's routes:
 * - only the view store's own routes are open: the rest of what Wow generates for its aggregates, and the command
 *   facade for its commands, answer not found ([ViewStoreRouteGuard]);
 * - the server generates every aggregate id, so a `Command-Aggregate-Id` a caller sends is dropped;
 * - the application comes from `CoSec-App-Id` only, so a `Command-Header-app_id` a caller sends is dropped;
 * - the tenant and owner come from the path only: a path whose tenant or owner is empty or holds whitespace or
 *   control characters is refused with [ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED] (Wow would read a blank one as
 *   missing and fall back to the headers), and a `Command-Tenant-Id` or `Command-Owner-Id` a caller sends is dropped;
 * - a system view is read-only, so a write addressed to one is refused with [ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY]
 *   instead of reading as a view that does not exist.
 *
 * Other requests pass untouched.
 */
class ViewStoreWebFilter(
    private val paths: ViewStorePaths,
    private val systemViewProvider: SystemViewProvider,
    private val routeGuard: ViewStoreRouteGuard,
) : WebFilter {
    companion object {
        private val WRITE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)

        /** The request header Wow's extend appender turns into the command header `app_id`. */
        val APP_ID_COMMAND_HEADER = CommandComponent.Header.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER

        /** The request headers a caller may not set on the view store's paths. */
        private val CLIENT_HEADERS = listOf(
            CommandComponent.Header.AGGREGATE_ID,
            CommandComponent.Header.TENANT_ID,
            CommandComponent.Header.OWNER_ID,
            APP_ID_COMMAND_HEADER,
        )
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val path = request.path.pathWithinApplication()
        if (routeGuard.isClosed(request.method, path)) {
            return exchange.writeError(
                NotFoundResourceException("Route [${request.method} ${path.value()}] is not found.")
            )
        }
        if (routeGuard.isViewStoreFacadeCommand(request.method, path, request.headers)) {
            return exchange.writeError(
                NotFoundResourceException("The view store's commands are not served by the command facade.")
            )
        }
        if (!paths.isViewStorePath(path)) {
            return chain.filter(exchange)
        }
        if (!paths.hasValidScope(path)) {
            return exchange.writeError(
                ViewStoreException(
                    ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED,
                    "The path's tenant and owner must not be blank or contain whitespace or control characters.",
                )
            )
        }
        return filterViewStore(exchange.withoutClientHeaders(), path, chain)
    }

    private fun filterViewStore(filtered: ServerWebExchange, path: PathContainer, chain: WebFilterChain): Mono<Void> {
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

    /**
     * Drops the headers a caller may not set: the aggregate id, the tenant and owner (the path's are the only ones),
     * and the application of the command header.
     */
    private fun ServerWebExchange.withoutClientHeaders(): ServerWebExchange {
        val names = request.headers.headerNames().filter { name ->
            CLIENT_HEADERS.any { it.equals(name, ignoreCase = true) }
        }
        if (names.isEmpty()) {
            return this
        }
        return mutate().request { builder ->
            builder.headers { headers -> names.forEach { headers.remove(it) } }
        }.build()
    }

    private fun ServerWebExchange.readOnly(viewId: String): Mono<Void> = writeError(
        ViewStoreException(ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY, "System view [$viewId] is read-only.")
    )

    private fun ServerWebExchange.writeError(error: Throwable): Mono<Void> {
        val errorInfo = error.toErrorInfo()
        response.statusCode = errorInfo.toHttpStatus()
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.set(ERROR_CODE, errorInfo.errorCode)
        val body = response.bufferFactory().wrap(errorInfo.toJsonString().toByteArray(Charsets.UTF_8))
        return response.writeWith(Mono.just(body))
    }
}
