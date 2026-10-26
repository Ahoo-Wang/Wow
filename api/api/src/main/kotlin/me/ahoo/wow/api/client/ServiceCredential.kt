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

package me.ahoo.wow.api.client

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
