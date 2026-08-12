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

import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryRequest
import java.util.Collections

class QueryAdmissionContext(
    val request: QueryRequest,
    val operation: QueryOperation,
    entryProvenances: Set<QueryProvenance>,
    val correlationId: String
) {
    val entryProvenances: Set<QueryProvenance> = Collections.unmodifiableSet(LinkedHashSet(entryProvenances))

    init {
        require(this.entryProvenances.isNotEmpty()) { "entryProvenances cannot be empty." }
        require(correlationId.isNotBlank()) { "correlationId cannot be blank." }
    }

    operator fun component1(): QueryRequest = request

    operator fun component2(): QueryOperation = operation

    operator fun component3(): Set<QueryProvenance> = entryProvenances

    operator fun component4(): String = correlationId

    fun copy(
        request: QueryRequest = this.request,
        operation: QueryOperation = this.operation,
        entryProvenances: Set<QueryProvenance> = this.entryProvenances,
        correlationId: String = this.correlationId
    ): QueryAdmissionContext = QueryAdmissionContext(request, operation, entryProvenances, correlationId)

    override fun equals(other: Any?): Boolean = other is QueryAdmissionContext &&
        request == other.request && operation == other.operation &&
        entryProvenances == other.entryProvenances && correlationId == other.correlationId

    override fun hashCode(): Int {
        var result = request.hashCode()
        result = 31 * result + operation.hashCode()
        result = 31 * result + entryProvenances.hashCode()
        return 31 * result + correlationId.hashCode()
    }

    override fun toString(): String =
        "QueryAdmissionContext(operation=$operation, entryProvenances=$entryProvenances, " +
            "request=<redacted>, correlationId=<redacted>)"
}
