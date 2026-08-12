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
import me.ahoo.wow.query.plan.QueryPlanValidator
import me.ahoo.wow.query.policy.DefaultQueryPolicyChain
import me.ahoo.wow.query.policy.QueryPolicyDescriptor
import me.ahoo.wow.query.policy.SystemQueryPolicy
import me.ahoo.wow.query.result.DefaultResultPolicyChain
import me.ahoo.wow.query.validation.QueryExpressionValidator
import me.ahoo.wow.query.validation.QueryRequestValidator
import reactor.core.scheduler.Schedulers
import java.util.UUID

object QueryGatewayFactory {
    @JvmStatic
    fun create(configuration: QueryGatewayConfiguration): QueryGateway = DefaultQueryGatewayFactory.create(
        configuration,
        QueryGatewayStageObserver.NONE
    )
}

internal object DefaultQueryGatewayFactory {
    fun create(
        configuration: QueryGatewayConfiguration,
        stageObserver: QueryGatewayStageObserver
    ): QueryGateway {
        val expressionValidator = QueryExpressionValidator(configuration.structureLimits)
        val policyDescriptors = configuration.customPolicies.mapIndexed { index, policy ->
            QueryPolicyDescriptor("custom-$index", index, policy)
        }
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
                policyDescriptors,
                expressionValidator
            ),
            backendResolver = configuration.backendResolver,
            planner = DefaultQueryPlanner.create(
                configuration.enabledCapabilities,
                QueryPlanValidator()
            ),
            resultPolicyChain = DefaultResultPolicyChain(configuration.resultPolicies),
            metrics = QueryGatewayMetrics(
                configuration.meterRegistry,
                configuration.enabledCapabilities
            ),
            stageObserver = stageObserver
        )
    }
}
