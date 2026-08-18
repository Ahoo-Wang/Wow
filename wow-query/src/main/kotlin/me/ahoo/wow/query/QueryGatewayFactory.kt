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

package me.ahoo.wow.query

import me.ahoo.wow.query.invocation.QueryInvocationFactory
import me.ahoo.wow.query.metrics.QueryGatewayMetrics
import me.ahoo.wow.query.plan.DefaultQueryPlanner
import me.ahoo.wow.query.policy.DefaultQueryPolicyChain
import me.ahoo.wow.query.policy.QueryPolicyRegistration
import me.ahoo.wow.query.policy.SystemQueryPolicy
import me.ahoo.wow.query.result.DefaultResultPolicyChain
import me.ahoo.wow.query.validation.QueryExpressionValidator
import me.ahoo.wow.query.validation.QueryRequestValidator
import reactor.core.scheduler.Schedulers
import java.util.Collections
import java.util.UUID

object QueryGatewayFactory {
    @JvmStatic
    fun create(configuration: QueryGatewayConfiguration): QueryGateway = DefaultQueryGatewayFactory.create(
        configuration,
        QueryGatewayStageObserver.NONE
    )

    @JvmStatic
    fun create(
        configuration: QueryGatewayConfiguration,
        policyRegistrations: List<QueryPolicyRegistration>
    ): QueryGateway = DefaultQueryGatewayFactory.create(
        configuration,
        QueryGatewayStageObserver.NONE,
        policyRegistrations
    )
}

internal object DefaultQueryGatewayFactory {
    fun create(
        configuration: QueryGatewayConfiguration,
        stageObserver: QueryGatewayStageObserver,
        policyRegistrations: List<QueryPolicyRegistration> = fallbackRegistrations(configuration)
    ): QueryGateway {
        val expressionValidator = QueryExpressionValidator(configuration.structureLimits)
        val registrations = validateAndSnapshotRegistrations(configuration, policyRegistrations)
        return DefaultQueryGateway.create(
            invocationFactory = QueryInvocationFactory(
                admission = configuration.admission,
                clock = configuration.clock,
                zoneId = configuration.zoneId,
                systemBudgetLimit = configuration.systemBudgetLimit,
                deadlineScheduler = Schedulers.parallel(),
                correlationIdFactory = { UUID.randomUUID().toString() }
            ),
            requestValidator = QueryRequestValidator(configuration.structureLimits),
            schemaResolver = configuration.schemaResolver,
            policyChain = DefaultQueryPolicyChain(
                SystemQueryPolicy(configuration.systemBudgetLimit),
                registrations,
                expressionValidator
            ),
            backendResolver = configuration.backendResolver,
            planner = DefaultQueryPlanner.create(configuration.enabledCapabilities),
            resultPolicyChain = DefaultResultPolicyChain(configuration.resultPolicies),
            metrics = QueryGatewayMetrics(
                configuration.meterRegistry,
                configuration.enabledCapabilities
            ),
            stageObserver = stageObserver,
            enabledCapabilities = configuration.enabledCapabilities,
            structureLimits = configuration.structureLimits
        )
    }

    private fun validateAndSnapshotRegistrations(
        configuration: QueryGatewayConfiguration,
        registrations: List<QueryPolicyRegistration>
    ): List<QueryPolicyRegistration> {
        val snapshot = ArrayList(registrations)
        require(snapshot.map(QueryPolicyRegistration::descriptorId).distinct().size == snapshot.size) {
            "Query policy registration descriptors must be unique."
        }
        require(snapshot.none { it.policy is SystemQueryPolicy }) {
            "System query policy cannot be registered as a custom policy."
        }
        val ordered = snapshot.sortedWith(
            compareBy<QueryPolicyRegistration> { it.order }.thenBy { it.descriptorId }
        )
        require(ordered.size == configuration.customPolicies.size) {
            "Query policy registrations must match configured custom policies."
        }
        require(
            ordered.map(QueryPolicyRegistration::policy)
                .zip(configuration.customPolicies)
                .all { (registered, configured) -> registered === configured }
        ) {
            "Query policy registrations must preserve configured policy identity and order."
        }
        return Collections.unmodifiableList(ordered)
    }

    private fun fallbackRegistrations(configuration: QueryGatewayConfiguration): List<QueryPolicyRegistration> =
        configuration.customPolicies.mapIndexed { index, policy ->
            QueryPolicyRegistration("custom-$index", index, policy)
        }
}
