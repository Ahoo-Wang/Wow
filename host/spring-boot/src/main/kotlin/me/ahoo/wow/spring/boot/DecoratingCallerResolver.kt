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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * A resolver that answers through another one, [decorated], narrowing or completing its callers (a service's reserved
 * scopes, its sign-in): the request's credentials are still [decorated]'s, so what the host declares of them, the
 * OpenAPI security schemes ([WowOpenApiSecurity.of]), is [decorated]'s too.
 */
interface DecoratingCallerResolver : CallerResolver {
    /**
     * The resolver that reads the request's credentials, which this one only narrows or completes: it is what the
     * credentials are declared after, itself possibly decorating another.
     */
    val decorated: CallerResolver
}

/**
 * Requires a signed-in caller: [decorated]'s caller without a subject, the anonymous one, is no caller, so the edge
 * answers the request 401 on every route, as CoSec's resolver has it answered. For a service whose every route needs
 * a user, in development too, where the host's callers ([DevelopmentCallerResolver]) serve a request without
 * `Wow-User` as the anonymous caller.
 */
class SignedInCallerResolver(
    /** The resolver that reads the request's credentials; its anonymous caller is no caller here. */
    override val decorated: CallerResolver,
) : DecoratingCallerResolver {
    override suspend fun resolve(request: ServerRequest): Caller? =
        decorated.resolve(request)?.takeIf { it.subject != null }
}
