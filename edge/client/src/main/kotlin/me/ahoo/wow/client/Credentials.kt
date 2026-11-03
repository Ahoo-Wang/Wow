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

package me.ahoo.wow.client

import kotlinx.coroutines.currentCoroutineContext
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.wire.DevelopmentHeaders
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The credential a client forwards in user mode: the request headers that make the peer's public port
 * authenticate the end user a call acts for, its caller, as its own edge did (`Authorization`, and CoSec's
 * `CoSec-App-Id` when the application is part of what it authorizes). The peer decides the caller's
 * identity, tenant and scope from them alone: a client asserts nothing.
 */
fun interface UserCredential {
    suspend fun headers(caller: Caller): Map<String, String>

    companion object {
        /** Forwards nothing: the peer serves the call as an anonymous caller, if it lets one. */
        @JvmField
        val NONE: UserCredential = UserCredential { emptyMap() }

        /**
         * Forwards the headers of the request being served, as the host puts them in the coroutine context
         * ([ForwardedHeaders]); none outside such a request.
         */
        @JvmField
        val FORWARDED: UserCredential =
            UserCredential { currentCoroutineContext()[ForwardedHeaders]?.headers.orEmpty() }

        /**
         * Writes the caller itself as the development identity headers ([DevelopmentHeaders]), which a host reads in a
         * development profile alone, with no identity provider: its subject, tenant, owner, roles (the attribute
         * [DevelopmentHeaders.ROLES_ATTRIBUTE]) and application ([Caller.app]). A caller without a subject is the
         * anonymous caller of the peer's default tenant, sent with its application alone. The space, a cross-scope
         * grant and every other attribute have no header: the peer's development callers have none of them. For a
         * test or a service tried out on one machine (a test server's typed clients); a deployment's peer never
         * reads them.
         */
        @JvmField
        val DEVELOPMENT: UserCredential = UserCredential { caller -> developmentHeaders(caller) }

        private fun developmentHeaders(caller: Caller): Map<String, String> = buildMap {
            caller.app?.let { put(DevelopmentHeaders.APP, it) }
            val subject = caller.subject ?: return@buildMap
            put(DevelopmentHeaders.USER, subject)
            put(DevelopmentHeaders.TENANT, caller.tenantId)
            caller.scope.ownerId?.let { put(DevelopmentHeaders.OWNER, it) }
            caller.attributes[DevelopmentHeaders.ROLES_ATTRIBUTE]?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?.let { put(DevelopmentHeaders.ROLES, it.joinToString(",")) }
        }
    }
}

/**
 * The credential headers of the request a coroutine serves, which [UserCredential.FORWARDED] forwards:
 * the host's edge puts them in the context of the request's handler, so code that calls a peer while serving the
 * request calls it as the request's end user.
 */
class ForwardedHeaders(val headers: Map<String, String>) : AbstractCoroutineContextElement(ForwardedHeaders) {
    companion object Key : CoroutineContext.Key<ForwardedHeaders>

    override fun toString(): String = "ForwardedHeaders(${headers.keys})"
}
