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

package me.ahoo.wow.viewstore.server

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.viewstore.api.ViewStoreContext
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The calling application in development: the one the request names in `CoSec-App-Id`, the caller's standard attribute
 * [Caller.APP], which the edge records as each command's request fact `app_id` and the query policy keeps the queries
 * in. Like the caller the host's development callers ([callers]) read, it is taken as the request sends it; CoSec's
 * resolver takes only the application CoSec authorized.
 */
class RequestedAppCallers(private val callers: CallerResolver) : CallerResolver {
    override suspend fun resolve(request: ServerRequest): Caller? {
        val caller = callers.resolve(request) ?: return null
        val app = request.headers().firstHeader(ViewStoreContext.APP_ID_HEADER)?.trim()?.ifEmpty { null }
            ?: return caller
        return Caller.of(
            tenantId = caller.tenantId,
            scope = caller.scope,
            subject = caller.subject,
            crossScope = caller.crossScope,
            attributes = caller.attributes + (Caller.APP to listOf(app)),
            service = caller.service,
        )
    }
}
