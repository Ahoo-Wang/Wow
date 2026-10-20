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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The credential a service presents on its synchronous calls and peer reads (decision D193): the value of the
 * `Authorization` header, such as `Bearer <token>`, asked for on every call so that one issued for a while (a CoSec
 * service principal's token) can be renewed; null sends none.
 */
fun interface ServiceCredential {
    suspend fun authorization(): String?

    companion object {
        /** Presents no credential: the target serves the call only if it trusts its network. */
        @JvmField
        val NONE: ServiceCredential = ServiceCredential { null }

        /** Presents `Bearer <token>`, a service token the targets trust (`wow.http.services.<service>.tokens`). */
        @JvmStatic
        fun bearer(token: String): ServiceCredential {
            require(token.isNotBlank()) { "A service token is not blank." }
            val authorization = "Bearer $token"
            return ServiceCredential { authorization }
        }
    }
}

/**
 * The credential a client forwards in user mode (decision D259): the request headers that make the peer's public port
 * authenticate the end user a call acts for, its caller, as its own edge did (`Authorization`, and CoSec's
 * `CoSec-App-Id` when the application is part of what it authorizes, decision D236). The peer decides the caller's
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
    }
}

/**
 * The credential headers of the request a coroutine serves (decision D259), which [UserCredential.FORWARDED] forwards:
 * the host's edge puts them in the context of the request's handler, so code that calls a peer while serving the
 * request calls it as the request's end user.
 */
class ForwardedHeaders(val headers: Map<String, String>) : AbstractCoroutineContextElement(ForwardedHeaders) {
    companion object Key : CoroutineContext.Key<ForwardedHeaders>

    override fun toString(): String = "ForwardedHeaders(${headers.keys})"
}
