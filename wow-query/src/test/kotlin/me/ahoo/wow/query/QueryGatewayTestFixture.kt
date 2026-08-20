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

import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.result.ResultPolicy
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryStructureLimits
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal val GATEWAY_STATUS: LogicalField = LogicalField("state.status")
internal val GATEWAY_TARGET: QueryTarget = QueryTarget(
    object : NamedAggregate {
        override val contextName: String = "gateway"
        override val aggregateName: String = "order"
    },
    QueryDocumentKind.SNAPSHOT
)
internal val GATEWAY_SHAPE: QueryResultShape.Typed<String> = QueryResultShape.Typed(
    String::class.java,
    QueryProjection.Include(setOf(GATEWAY_STATUS))
)
internal val GATEWAY_LIMITS: QueryStructureLimits = QueryStructureLimits(16, 128, 128, 4096)

internal fun gatewaySchema(): QuerySchema = QuerySchema(
    GATEWAY_TARGET,
    QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT) +
        QueryFieldSchema.string(GATEWAY_STATUS, nullable = false).copy(sortable = true)
)

internal fun gatewayDescriptor(
    maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED
): QueryBackendDescriptor = QueryBackendDescriptor(
    backendId = "recording",
    documentKinds = setOf(QueryDocumentKind.SNAPSHOT),
    planVersions = setOf(QueryPlanVersion.V1),
    portableOperators = PortableOperator.entries.toSet(),
    portableFeatures = QueryPortableFeature.entries.toSet(),
    stringComparisonModes = StringComparisonMode.entries.toSet(),
    capabilities = emptySet(),
    maxBudget = maxBudget
)

internal fun gatewayConfiguration(
    backend: RecordingQueryBackend,
    customPolicies: List<QueryPolicy> = emptyList(),
    resultPolicies: List<ResultPolicy> = emptyList(),
    meterRegistry: MeterRegistry? = null,
    admission: QueryAdmission = QueryAdmission { context ->
        Mono.just(
            QueryInvocationScope(
                QueryAuthorityView("subject", "tenant", "owner", emptySet(), emptySet()),
                context.request.requestedScope,
                context.correlationId
            )
        )
    },
    schemaResolver: QuerySchemaResolver = object : QuerySchemaResolver {
        override fun resolve(target: QueryTarget): Mono<QuerySchemaView> = Mono.just(gatewaySchema())
    },
    backendResolver: QueryBackendResolver = QueryBackendResolver {
        ResolvedQueryBackend.resolve(backend, QueryBackendRouteIdentity("gateway-route"))
    },
    structureLimits: QueryStructureLimits = GATEWAY_LIMITS,
    systemBudgetLimit: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED,
    enabledCapabilities: Set<QueryCapabilityId> = emptySet(),
    clock: Clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
    zoneId: java.time.ZoneId = ZoneOffset.UTC
): QueryGatewayConfiguration = QueryGatewayConfiguration(
    admission = admission,
    schemaResolver = schemaResolver,
    backendResolver = backendResolver,
    customPolicies = customPolicies,
    resultPolicies = resultPolicies,
    clock = clock,
    zoneId = zoneId,
    structureLimits = structureLimits,
    systemBudgetLimit = systemBudgetLimit,
    enabledCapabilities = enabledCapabilities,
    meterRegistry = meterRegistry
)
