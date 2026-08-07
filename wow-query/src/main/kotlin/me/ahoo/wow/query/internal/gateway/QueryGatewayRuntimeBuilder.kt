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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.internal.gateway

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryGateway
import me.ahoo.wow.query.gateway.QueryGatewayConfiguration
import me.ahoo.wow.query.gateway.QueryLegacyDialectResolver
import me.ahoo.wow.query.gateway.QueryRawServiceSource
import me.ahoo.wow.query.gateway.QueryResultMaterializer
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.internal.admission.QueryAdmissionLimits
import me.ahoo.wow.query.internal.admission.RawAdmissionGuard
import me.ahoo.wow.query.internal.execution.LegacyBackendRegistry
import me.ahoo.wow.query.internal.execution.LegacyExecutionBinding
import me.ahoo.wow.query.internal.execution.QueryBackendRegistry
import me.ahoo.wow.query.internal.execution.QueryDeadlineEnforcer
import me.ahoo.wow.query.internal.execution.QueryExecutionRouteResolver
import me.ahoo.wow.query.internal.execution.QueryExecutor
import me.ahoo.wow.query.internal.normalization.QueryNormalizer
import me.ahoo.wow.query.internal.planning.QueryPlanner
import me.ahoo.wow.query.internal.policy.QueryExecutionContextFactory
import me.ahoo.wow.query.internal.policy.QueryPolicyEnforcer
import me.ahoo.wow.query.internal.policy.TenantIsolationQueryPolicy
import me.ahoo.wow.query.internal.schema.QuerySchemaRegistry
import me.ahoo.wow.serialization.MessageRecords
import reactor.core.scheduler.Scheduler
import java.time.Clock
import me.ahoo.wow.query.internal.execution.QueryGateway as InternalQueryGateway
import me.ahoo.wow.query.internal.model.QueryDocumentKind as InternalDocumentKind
import me.ahoo.wow.query.internal.model.QueryTarget as InternalQueryTarget

internal object QueryGatewayRuntimeBuilder {
    fun build(
        namedAggregates: Iterable<NamedAggregate>,
        rawServiceSource: QueryRawServiceSource,
        resultMaterializers: Iterable<QueryResultMaterializer<*>>,
        dialectResolver: QueryLegacyDialectResolver,
        authorityResolver: QueryAuthorityResolver,
        configuration: QueryGatewayConfiguration,
        clock: Clock,
        scheduler: Scheduler,
    ): QueryGateway {
        val aggregates = materializeAggregates(namedAggregates)
        val targets = aggregates.flatMap { aggregate ->
            listOf(
                TargetBinding(
                    InternalQueryTarget(aggregate, InternalDocumentKind.SNAPSHOT),
                    rawServiceSource.snapshot(aggregate),
                    MessageRecords.AGGREGATE_ID,
                ),
                TargetBinding(
                    InternalQueryTarget(aggregate, InternalDocumentKind.EVENT_STREAM),
                    rawServiceSource.eventStream(aggregate),
                    MessageRecords.ID,
                ),
            )
        }
        val schemas = QuerySchemaRegistry(targets.map { binding -> legacyQuerySchema(binding.target) })
        val legacyBindings = targets.map { binding ->
            val publicTarget = binding.target.toPublic()
            LegacyExecutionBinding.create(
                binding.target,
                LegacyDynamicQueryCompiler(
                    binding.target,
                    dialectResolver.resolve(publicTarget),
                    binding.identityField,
                ),
                LegacyDynamicQueryBackend(binding.queryService),
            )
        }
        val deadlineEnforcer = QueryDeadlineEnforcer(clock, scheduler)
        val plannedRegistry = QueryBackendRegistry(emptyList(), emptyMap())
        val routeResolver = QueryExecutionRouteResolver(
            plannedRegistry,
            LegacyBackendRegistry(legacyBindings),
        )
        val delegate = InternalQueryGateway(
            admissionGuard = RawAdmissionGuard(QueryAdmissionLimits.DEFAULT),
            normalizer = QueryNormalizer(clock),
            schemaRegistry = schemas,
            contextFactory = QueryExecutionContextFactory(GatewayAuthorityProvider(authorityResolver), clock),
            policyEnforcer = QueryPolicyEnforcer(TenantIsolationQueryPolicy()),
            planner = QueryPlanner(),
            routeResolver = routeResolver,
            executor = QueryExecutor(deadlineEnforcer),
            deadlineEnforcer = deadlineEnforcer,
        )
        return DefaultQueryGateway(delegate, configuration, resultMaterializers)
    }

    private fun materializeAggregates(namedAggregates: Iterable<NamedAggregate>) =
        namedAggregates.map(NamedAggregate::materialize).also { aggregates ->
            require(aggregates.distinct().size == aggregates.size) {
                "Query Gateway aggregate targets must be unique."
            }
        }

    private fun InternalQueryTarget.toPublic(): QueryTarget =
        QueryTarget(
            namedAggregate,
            when (documentKind) {
                InternalDocumentKind.SNAPSHOT -> QueryDocumentKind.SNAPSHOT
                InternalDocumentKind.EVENT_STREAM -> QueryDocumentKind.EVENT_STREAM
            },
        )

    private data class TargetBinding(
        val target: InternalQueryTarget,
        val queryService: QueryService<*>,
        val identityField: String,
    )
}
