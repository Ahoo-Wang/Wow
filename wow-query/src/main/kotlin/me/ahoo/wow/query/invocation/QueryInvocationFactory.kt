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
import me.ahoo.wow.query.validation.QueryBudgetLimit
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal class QueryInvocationFactory(
    private val admission: QueryAdmission,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val systemBudgetLimit: QueryBudgetLimit,
    private val correlationIdFactory: () -> String
) {
    fun admit(
        request: QueryRequest,
        operation: QueryOperation,
        entryProvenance: QueryProvenance
    ): Mono<QueryInvocationSeed> = Mono.defer {
        val frozenInstant = clock.instant()
        val correlationId = correlationIdFactory().also {
            require(it.isNotBlank()) { "correlationId cannot be blank." }
        }
        val admissionBudget = QueryBudgetLimit.min(
            requestHint = request.budget,
            systemLimit = systemBudgetLimit,
            policyLimit = QueryBudgetLimit.UNBOUNDED,
            backendLimit = QueryBudgetLimit.UNBOUNDED
        )
        val admissionDeadline = admissionBudget.timeout.toDeadline(frozenInstant)
        val context = QueryAdmissionContext(request, operation, entryProvenance, correlationId)
        admission.admit(context).map { scope ->
            require(scope.correlationId == correlationId) {
                "Query admission must preserve the invocation correlationId."
            }
            require(scope.requestedScope == request.requestedScope) {
                "Query admission must preserve the requested scope."
            }
            QueryInvocationSeed(
                request = request,
                operation = operation,
                entryProvenance = entryProvenance,
                scope = scope,
                frozenInstant = frozenInstant,
                zoneId = zoneId,
                admissionDeadline = admissionDeadline,
                admissionBudget = admissionBudget
            )
        }
    }

    private fun Duration?.toDeadline(frozenInstant: Instant): Instant? = when {
        this == null -> null
        isZero -> frozenInstant
        else -> frozenInstant.plus(this)
    }
}
