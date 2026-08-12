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

package me.ahoo.wow.query.invocation

import me.ahoo.wow.api.query.gateway.RequestedQueryScope

class QueryInvocationScope(
    val trustedAuthority: QueryAuthorityView,
    val requestedScope: RequestedQueryScope,
    val correlationId: String
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId cannot be blank." }
    }

    operator fun component1(): QueryAuthorityView = trustedAuthority

    operator fun component2(): RequestedQueryScope = requestedScope

    operator fun component3(): String = correlationId

    fun copy(
        trustedAuthority: QueryAuthorityView = this.trustedAuthority,
        requestedScope: RequestedQueryScope = this.requestedScope,
        correlationId: String = this.correlationId
    ): QueryInvocationScope = QueryInvocationScope(trustedAuthority, requestedScope, correlationId)

    override fun equals(other: Any?): Boolean = other is QueryInvocationScope &&
        trustedAuthority == other.trustedAuthority && requestedScope == other.requestedScope &&
        correlationId == other.correlationId

    override fun hashCode(): Int {
        var result = trustedAuthority.hashCode()
        result = 31 * result + requestedScope.hashCode()
        return 31 * result + correlationId.hashCode()
    }

    override fun toString(): String =
        "QueryInvocationScope(trustedAuthority=<redacted>, requestedScope=<redacted>, correlationId=<redacted>)"
}
