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

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.query.validation.QueryBudgetLimit
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.time.Clock
import java.time.ZoneId
import java.util.Collections

internal class QueryInvocationFactory(
    private val admission: QueryAdmission,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val systemBudgetLimit: QueryBudgetLimit,
    private val deadlineScheduler: Scheduler,
    private val correlationIdFactory: () -> String
) {
    fun admit(
        request: QueryRequest,
        operation: QueryOperation
    ): Mono<QueryInvocationSeed> = admitInternal(
        request = request,
        operation = operation,
        expressionContributions = mapOf(QueryProvenance.CALLER_REQUEST to request.expression)
    )

    fun admitLegacy(
        request: QueryRequest,
        operation: QueryOperation,
        legacyExpression: QueryExpression
    ): Mono<QueryInvocationSeed> = admitInternal(
        request = request,
        operation = operation,
        expressionContributions = linkedMapOf(
            QueryProvenance.CALLER_REQUEST to request.expression,
            QueryProvenance.LEGACY_ENRICHMENT to legacyExpression
        )
    )

    private fun admitInternal(
        request: QueryRequest,
        operation: QueryOperation,
        expressionContributions: Map<QueryProvenance, QueryExpression>
    ): Mono<QueryInvocationSeed> {
        val contributionSnapshot = Collections.unmodifiableMap(LinkedHashMap(expressionContributions))
        return Mono.defer {
            val frozenInstant = clock.instant()
            val deadlineGuard = QueryDeadlineGuard.anchor(frozenInstant, deadlineScheduler)
            val correlationId = correlationIdFactory().also {
                require(it.isNotBlank()) { "correlationId cannot be blank." }
            }
            val admissionBudget = QueryBudgetLimit.min(
                requestHint = request.budget,
                systemLimit = systemBudgetLimit,
                policyLimit = QueryBudgetLimit.UNBOUNDED,
                backendLimit = QueryBudgetLimit.UNBOUNDED
            )
            val admissionDeadline = QueryDeadline.from(frozenInstant, admissionBudget.timeout)
            val context = QueryAdmissionContext(request, operation, contributionSnapshot.keys, correlationId)
            val admitted = admission.admit(context)
                .switchIfEmpty(
                    Mono.error(
                        QueryException(
                            QueryErrorCode.POLICY_FAILURE,
                            QueryStage.ADMISSION,
                            QueryErrorReason.POLICY_EVALUATION_FAILED
                        )
                    )
                ).map { scope ->
                    require(scope.correlationId == correlationId) {
                        "Query admission must preserve the invocation correlationId."
                    }
                    require(scope.requestedScope == request.requestedScope) {
                        "Query admission must preserve the requested scope."
                    }
                    QueryInvocationSeed(
                        request = request,
                        operation = operation,
                        expressionContributions = contributionSnapshot,
                        scope = scope,
                        frozenInstant = frozenInstant,
                        zoneId = zoneId,
                        admissionDeadline = admissionDeadline,
                        admissionBudget = admissionBudget,
                        deadlineGuard = deadlineGuard
                    )
                }
            deadlineGuard.enforce(admitted, admissionDeadline, QueryStage.ADMISSION)
                .onErrorMap { error -> mapAdmissionError(error) }
        }
    }

    private fun mapAdmissionError(error: Throwable): Throwable = when (error) {
        is QueryDeadlineExceededException -> QueryException(
            QueryErrorCode.DEADLINE_EXCEEDED,
            QueryStage.ADMISSION,
            QueryErrorReason.DEADLINE_REACHED
        )

        else -> error
    }
}
