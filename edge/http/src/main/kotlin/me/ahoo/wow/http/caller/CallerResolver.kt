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

package me.ahoo.wow.http.caller

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.runtime.admission.Caller
import org.springframework.web.reactive.function.server.ServerRequest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Who sends a request to the public port (decision D93): the edge authenticates, admission authorizes.
 *
 * A resolver reads the request's credentials, and answers null when it accepts none: the request is answered 401
 * `wow.auth.unauthenticated`. It also decides the space of a request that names one (`Wow-Space`, decision D96):
 * the caller it returns acts in that space when the principal belongs to it; a caller whose space is not the one
 * the request named is answered 403 `wow.auth.forbidden`, never served in another space.
 */
fun interface CallerResolver {
    suspend fun resolve(request: ServerRequest): Caller?

    companion object {
        /**
         * Every request is [Caller.ANONYMOUS]: the default tenant, no scope (decision D97). For a service without
         * authentication; it warns once, on the first request it resolves.
         */
        @JvmField
        val ANONYMOUS: CallerResolver = AnonymousCallerResolver()
    }
}

private class AnonymousCallerResolver : CallerResolver {
    private val warned = AtomicBoolean()

    override suspend fun resolve(request: ServerRequest): Caller {
        if (warned.compareAndSet(false, true)) {
            logger.warn {
                "No CallerResolver is configured: every request is served as the anonymous caller " +
                    "(tenant ${Caller.DEFAULT_TENANT}, no scope)."
            }
        }
        return Caller.ANONYMOUS
    }

    override fun toString(): String = "CallerResolver.ANONYMOUS"

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
