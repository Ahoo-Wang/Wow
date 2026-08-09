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

@file:OptIn(
    me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.query.internal.gateway

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.backend.AnalyticsQueryCursorLifecycle
import me.ahoo.wow.query.backend.BackendAnalyticsCursorState
import me.ahoo.wow.query.backend.BackendStreamSupport
import me.ahoo.wow.query.backend.QueryBackendComposition
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.cursor.QueryCursorLeaseConfiguration
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryExecutionMode
import me.ahoo.wow.query.gateway.QueryExecutionProfiles
import me.ahoo.wow.query.gateway.QueryGateway
import me.ahoo.wow.query.gateway.QueryLegacyDialectResolver
import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryRawServiceSource
import me.ahoo.wow.query.gateway.QueryResultMaterializer
import me.ahoo.wow.query.gateway.QueryRuntimeHealthKind
import me.ahoo.wow.query.gateway.QueryRuntimeHealthObservation
import me.ahoo.wow.query.gateway.QueryRuntimeHealthObserver
import me.ahoo.wow.query.gateway.QueryShadowConfiguration
import me.ahoo.wow.query.gateway.QueryShadowObserver
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.internal.admission.QueryAdmissionLimits
import me.ahoo.wow.query.internal.admission.RawAdmissionGuard
import me.ahoo.wow.query.internal.cursor.PersistentQueryCursorBackendLeaseRegistration
import me.ahoo.wow.query.internal.cursor.PersistentQueryCursorLeaseCoordinator
import me.ahoo.wow.query.internal.cursor.PersistentQueryCursorLeaseManager
import me.ahoo.wow.query.internal.cursor.QueryCursorLeaseLimits
import me.ahoo.wow.query.internal.cursor.QueryCursorLeaseObserver
import me.ahoo.wow.query.internal.cursor.QueryCursorSigningKey
import me.ahoo.wow.query.internal.cursor.QueryCursorSigningKeyRing
import me.ahoo.wow.query.internal.execution.AnalyticsCursorRuntime
import me.ahoo.wow.query.internal.execution.BoundedQueryShadowSupervisor
import me.ahoo.wow.query.internal.execution.ExperimentalAnalyticsBackendAdapter
import me.ahoo.wow.query.internal.execution.LegacyBackendRegistry
import me.ahoo.wow.query.internal.execution.LegacyExecutionBinding
import me.ahoo.wow.query.internal.execution.QueryBackendDescriptor
import me.ahoo.wow.query.internal.execution.QueryBackendKey
import me.ahoo.wow.query.internal.execution.QueryBackendRegistration
import me.ahoo.wow.query.internal.execution.QueryBackendRegistry
import me.ahoo.wow.query.internal.execution.QueryBackendStreamSupport
import me.ahoo.wow.query.internal.execution.QueryDeadlineEnforcer
import me.ahoo.wow.query.internal.execution.QueryDecisionObserver
import me.ahoo.wow.query.internal.execution.QueryExecutionRouteResolver
import me.ahoo.wow.query.internal.execution.QueryExecutor
import me.ahoo.wow.query.internal.execution.QueryFallback
import me.ahoo.wow.query.internal.execution.QueryShadowSupervisorFailure
import me.ahoo.wow.query.internal.normalization.QueryNormalizer
import me.ahoo.wow.query.internal.plan.SemanticTier
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
        clock: Clock,
        scheduler: Scheduler,
        backendComposition: QueryBackendComposition = QueryBackendComposition.EMPTY,
        executionProfiles: QueryExecutionProfiles,
        shadowConfiguration: QueryShadowConfiguration,
        shadowObserver: QueryShadowObserver,
        runtimeHealthObserver: QueryRuntimeHealthObserver,
        cursorLeaseConfiguration: QueryCursorLeaseConfiguration? = null,
    ): QueryGatewayRuntimeComponents {
        val aggregates = materializeAggregates(namedAggregates)
        val targets = createTargetBindings(aggregates, rawServiceSource)
        val contributedSchemas = (
            backendComposition.contributions.map { contribution -> contribution.schema } +
                backendComposition.notReadyBackends.map { backend -> backend.schema }
            ).groupBy(QueryDocumentSchema::target)
        validateContributedSchemas(targets, contributedSchemas)
        validateExecutionProfiles(
            targets.map { binding -> binding.target.toPublic() },
            backendComposition,
            executionProfiles,
            shadowObserver,
            runtimeHealthObserver,
        )
        val schemas = createSchemaRegistry(targets, contributedSchemas)
        val legacyBindings = createLegacyBindings(targets, dialectResolver)
        val deadlineEnforcer = QueryDeadlineEnforcer(clock, scheduler)
        val trustedAuthorityChannel = TrustedAuthorityChannel.create()
        val plannedRegistry = createPlannedRegistry(backendComposition)
        val routeResolver = QueryExecutionRouteResolver(
            plannedRegistry,
            LegacyBackendRegistry(legacyBindings),
        )
        val shadowSupervisor = BoundedQueryShadowSupervisor(shadowConfiguration, shadowObserver)
        val delegate = InternalQueryGateway(
            admissionGuard = RawAdmissionGuard(QueryAdmissionLimits.DEFAULT),
            normalizer = QueryNormalizer(clock),
            schemaRegistry = schemas,
            contextFactory = QueryExecutionContextFactory(
                GatewayAuthorityProvider(trustedAuthorityChannel, authorityResolver),
                clock,
            ),
            policyEnforcer = QueryPolicyEnforcer(TenantIsolationQueryPolicy()),
            planner = QueryPlanner(),
            routeResolver = routeResolver,
            executor = QueryExecutor(
                deadlineEnforcer = deadlineEnforcer,
                shadowSupervisor = shadowSupervisor,
                decisionObserver = RuntimeDecisionObserver(runtimeHealthObserver),
                shadowProbeTimeout = shadowConfiguration.probeTimeout,
            ),
            deadlineEnforcer = deadlineEnforcer,
            analyticsCursorRuntime = cursorLeaseConfiguration?.toRuntime(
                clock,
                backendComposition,
                runtimeHealthObserver,
            ),
        )
        return QueryGatewayRuntimeComponents(
            DefaultQueryGateway(delegate, executionProfiles, resultMaterializers),
            DefaultAnalyticsQueryGateway(delegate, executionProfiles),
            trustedAuthorityChannel,
            delegate::reapExpiredAnalyticsCursors,
        )
    }

    private fun validateContributedSchemas(
        targets: List<TargetBinding>,
        contributedSchemas: Map<InternalQueryTarget, List<QueryDocumentSchema>>,
    ) {
        require(contributedSchemas.keys.all { target -> targets.any { binding -> binding.target == target } }) {
            "Query backend composition contains an unknown aggregate target."
        }
        contributedSchemas.values.forEach { schemas ->
            require(schemas.map(QueryDocumentSchema::contractId).distinct().size == 1) {
                "Query backend contributions for one target must share a schema contract."
            }
        }
    }

    private fun createSchemaRegistry(
        targets: List<TargetBinding>,
        contributedSchemas: Map<InternalQueryTarget, List<QueryDocumentSchema>>,
    ): QuerySchemaRegistry = QuerySchemaRegistry(
        targets.map { binding ->
            contributedSchemas[binding.target]?.first() ?: legacyQuerySchema(binding.target)
        },
    )

    private fun createLegacyBindings(
        targets: List<TargetBinding>,
        dialectResolver: QueryLegacyDialectResolver,
    ): List<LegacyExecutionBinding> = targets.map { binding ->
        LegacyExecutionBinding.create(
            binding.target,
            LegacyDynamicQueryCompiler(
                binding.target,
                dialectResolver.resolve(binding.target.toPublic()),
                binding.identityField,
            ),
            LegacyDynamicQueryBackend(binding.queryService),
        )
    }

    private fun createPlannedRegistry(composition: QueryBackendComposition): QueryBackendRegistry =
        QueryBackendRegistry(
            composition.contributions.map { contribution ->
                QueryBackendRegistration(
                    descriptor = QueryBackendDescriptor(
                        key = QueryBackendKey(contribution.schema.target, contribution.backendId),
                        schemaContractId = contribution.schema.contractId,
                        supportedOperations = contribution.supportedOperations,
                        semanticTiers = contribution.semanticTiers.mapTo(linkedSetOf()) { tier ->
                            SemanticTier.valueOf(tier.name)
                        },
                        fieldCapabilities = contribution.fieldCapabilities,
                        searchScopes = contribution.searchScopes,
                        mappingGenerationDigest = contribution.mappingGenerationDigest,
                        streamSupport = when (contribution.streamSupport) {
                            BackendStreamSupport.NONE -> QueryBackendStreamSupport.NONE
                            BackendStreamSupport.BOUNDED_ONLY -> QueryBackendStreamSupport.BOUNDED_ONLY
                        },
                    ),
                    experimentalRecordBackend = contribution.backend,
                    analyticsBackend = contribution.analyticsBackend?.let { backend ->
                        ExperimentalAnalyticsBackendAdapter(backend, contribution.schema)
                    },
                )
            },
            composition.defaultRoutes,
            composition.notReadyBackends.mapTo(linkedSetOf()) { backend ->
                QueryBackendKey(backend.schema.target, backend.backendId)
            },
        )

    private fun materializeAggregates(namedAggregates: Iterable<NamedAggregate>) =
        namedAggregates.map(NamedAggregate::materialize).also { aggregates ->
            require(aggregates.distinct().size == aggregates.size) {
                "Query Gateway aggregate targets must be unique."
            }
        }

    private fun createTargetBindings(
        aggregates: List<NamedAggregate>,
        rawServiceSource: QueryRawServiceSource,
    ): List<TargetBinding> = aggregates.flatMap { aggregate ->
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

    private fun validateExecutionProfiles(
        targets: List<QueryTarget>,
        backendComposition: QueryBackendComposition,
        profiles: QueryExecutionProfiles,
        shadowObserver: QueryShadowObserver,
        runtimeHealthObserver: QueryRuntimeHealthObserver,
    ) {
        val knownTargets = targets.toSet()
        require(knownTargets.containsAll(profiles.targetProfiles.keys)) {
            "Query execution profiles contain an unknown aggregate target."
        }
        require(knownTargets.containsAll(profiles.operationProfiles.keys.map { key -> key.target })) {
            "Query operation profiles contain an unknown aggregate target."
        }
        val contributionByTargetAndBackend = backendComposition.contributions.associateBy { contribution ->
            contribution.schema.target to contribution.backendId
        }
        val notReadyKeys = backendComposition.notReadyBackends.mapTo(linkedSetOf()) { backend ->
            backend.schema.target to backend.backendId
        }
        targets.forEach { target ->
            RUNTIME_OPERATIONS.forEach { operation ->
                val profile = profiles.resolve(target, operation)
                if (profile.executionMode == QueryExecutionMode.LEGACY) {
                    return@forEach
                }
                val backendId = requireNotNull(backendComposition.defaultRoutes[target]) {
                    "Non-legacy Query execution profile requires a default Backend route for $target."
                }
                val key = target to backendId
                if (key in notReadyKeys) {
                    require(profile.executionMode == QueryExecutionMode.SHADOW) {
                        "PLANNED Query execution profile requires a ready Backend contribution for $target/$backendId."
                    }
                    return@forEach
                }
                val contribution = requireNotNull(contributionByTargetAndBackend[key]) {
                    "Non-legacy Query execution profile requires a Backend contribution for $target/$backendId."
                }
                val explicitlyScoped = profiles.operationProfiles.containsKey(
                    me.ahoo.wow.query.gateway.QueryOperationProfileKey(target, operation),
                )
                if (profile.executionMode == QueryExecutionMode.PLANNED || explicitlyScoped) {
                    require(operation in contribution.supportedOperations) {
                        "Query Backend $backendId does not support configured operation $target/$operation."
                    }
                }
            }
        }
        val shadowConfigured = targets.any { target ->
            RUNTIME_OPERATIONS.any { operation ->
                profiles.resolve(target, operation).executionMode == QueryExecutionMode.SHADOW
            }
        }
        require(!shadowConfigured || shadowObserver !== QueryShadowObserver.NONE) {
            "SHADOW Query execution requires a QueryShadowObserver."
        }
        require(!shadowConfigured || runtimeHealthObserver !== QueryRuntimeHealthObserver.NONE) {
            "SHADOW Query execution requires a QueryRuntimeHealthObserver."
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

    private val RUNTIME_OPERATIONS = listOf(
        QueryOperation.SINGLE,
        QueryOperation.STREAM,
        QueryOperation.PAGE,
        QueryOperation.COUNT,
        QueryOperation.ANALYZE,
    )

    @OptIn(me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class)
    private fun QueryCursorLeaseConfiguration.toRuntime(
        clock: Clock,
        backendComposition: QueryBackendComposition,
        runtimeHealthObserver: QueryRuntimeHealthObserver,
    ): AnalyticsCursorRuntime {
        val keyRing = QueryCursorSigningKeyRing(
            QueryCursorSigningKey(signingKeys.current.id, signingKeys.current.secretCopy()),
            signingKeys.previous.map { key -> QueryCursorSigningKey(key.id, key.secretCopy()) },
        )
        val manager = PersistentQueryCursorLeaseManager(
            store,
            keyRing,
            clock,
            QueryCursorLeaseLimits(
                maxEntries = Int.MAX_VALUE,
                maxTtl = maxCursorTtl,
                maxBackendStateBytes = maxBackendStateBytes,
            ),
        )
        val registrations = backendComposition.contributions.mapNotNull { contribution ->
            val lifecycle = contribution.analyticsBackend as? AnalyticsQueryCursorLifecycle ?: return@mapNotNull null
            PersistentQueryCursorBackendLeaseRegistration(
                contribution.schema.target,
                contribution.backendId,
            ) { state ->
                lifecycle.close(BackendAnalyticsCursorState(state.payload()))
            }
        }
        return AnalyticsCursorRuntime(
            PersistentQueryCursorLeaseCoordinator(
                manager,
                registrations,
                RuntimeCursorLeaseObserver(runtimeHealthObserver),
            ),
            leaseTtl,
            clock,
        )
    }
}

private class RuntimeDecisionObserver(
    private val delegate: QueryRuntimeHealthObserver,
) : QueryDecisionObserver {
    override fun onFallback(fallback: QueryFallback) {
        delegate.observe(
            fallback.target.toPublicTarget(),
            fallback.operation.name,
            QueryRuntimeHealthKind.FALLBACK,
            fallback.issues.values.first().code.name,
        )
    }

    override fun onShadowSupervisorFailure(failure: QueryShadowSupervisorFailure) {
        delegate.observe(
            failure.task.target.toPublicTarget(),
            failure.task.operation.name,
            QueryRuntimeHealthKind.SHADOW_SUPERVISOR_FAILURE,
            failure.issue.code.name,
        )
    }
}

private class RuntimeCursorLeaseObserver(
    private val delegate: QueryRuntimeHealthObserver,
) : QueryCursorLeaseObserver {
    override fun onCleanupFailure(
        descriptor: me.ahoo.wow.query.internal.cursor.QueryCursorLeaseDescriptor,
        reason: me.ahoo.wow.query.internal.cursor.QueryCursorCleanupReason,
        error: Throwable,
    ) {
        delegate.observe(
            descriptor.target,
            QueryOperation.ANALYZE.name,
            QueryRuntimeHealthKind.CURSOR_CLEANUP_FAILURE,
            "CURSOR_${reason.name}_CLEANUP_FAILED",
        )
    }
}

private fun QueryRuntimeHealthObserver.observe(
    target: QueryTarget,
    operation: String,
    kind: QueryRuntimeHealthKind,
    reasonCode: String,
) {
    try {
        onObservation(QueryRuntimeHealthObservation(target, QueryOperation.valueOf(operation), kind, reasonCode))
    } catch (_: RuntimeException) {
        // Runtime observability cannot alter query execution or cleanup.
    }
}

private fun InternalQueryTarget.toPublicTarget(): QueryTarget = QueryTarget(
    namedAggregate,
    when (documentKind) {
        InternalDocumentKind.SNAPSHOT -> QueryDocumentKind.SNAPSHOT
        InternalDocumentKind.EVENT_STREAM -> QueryDocumentKind.EVENT_STREAM
    },
)

internal data class QueryGatewayRuntimeComponents(
    val gateway: QueryGateway,
    val analyticsGateway: me.ahoo.wow.query.gateway.AnalyticsQueryGateway,
    val trustedAuthorityChannel: TrustedAuthorityChannel,
    val cursorReaper: (Int) -> reactor.core.publisher.Mono<Long>,
)
