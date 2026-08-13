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

package me.ahoo.wow.mongo.query.backend

import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryGatewayConfiguration
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyConstraints
import me.ahoo.wow.query.policy.QueryPolicyPermissions
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryStructureLimits
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class MongoQueryGatewayHarness(
    private val target: QueryTarget,
    private val schema: QuerySchema,
    private val backendFactory: QueryBackendFactory,
    systemBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED,
    fieldAccess: QueryFieldAccess = QueryFieldAccess.UNRESTRICTED,
    enabledCapabilities: Set<QueryCapabilityId> = setOf(
        QueryCapabilityId(MongoQueryBackendFactory.FULL_TEXT_CAPABILITY),
        QueryCapabilityId(MongoQueryBackendFactory.NATIVE_CAPABILITY)
    )
) {
    val gateway: QueryGateway

    init {
        val schemaCapabilities = schema.fields.values.flatMap { it.capabilities }.toSet()
        val capabilityAccess = enabledCapabilities.intersect(schemaCapabilities)
            .associateWith { CapabilityDecision.GRANT }
        val policy = QueryPolicy {
            Mono.just(
                QueryPolicyResult(
                    constraints = QueryPolicyConstraints(
                        fieldAccess = fieldAccess,
                        capabilityAccess = capabilityAccess
                    )
                )
            )
        }
        val resolver = object : QueryBackendResolver {
            override fun resolve(target: QueryTarget) =
                Mono.error<ResolvedQueryBackend>(AssertionError("Target-only backend resolution is forbidden."))

            override fun resolve(context: me.ahoo.wow.query.backend.QueryBackendResolutionContext) = Mono.defer {
                ResolvedQueryBackend.resolve(backendFactory.bind(context), ROUTE_IDENTITY)
            }
        }
        gateway = QueryGatewayFactory.create(
            QueryGatewayConfiguration(
                admission = QueryAdmission { context ->
                    Mono.just(
                        QueryInvocationScope(
                            trustedAuthority = QueryAuthorityView(
                                subjectId = "mongo-query-integration",
                                tenantId = null,
                                ownerId = null,
                                spaceIds = emptySet(),
                                permissions = setOf(QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS)
                            ),
                            requestedScope = context.request.requestedScope,
                            correlationId = context.correlationId
                        )
                    )
                },
                schemaResolver = object : QuerySchemaResolver {
                    override fun resolve(target: QueryTarget): Mono<QuerySchemaView> =
                        if (target == this@MongoQueryGatewayHarness.target) Mono.just(schema) else Mono.empty()
                },
                backendResolver = resolver,
                customPolicies = listOf(policy),
                resultPolicies = emptyList(),
                clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                zoneId = ZoneOffset.UTC,
                structureLimits = QueryStructureLimits(32, 512, 512, 64 * 1024),
                systemBudgetLimit = systemBudget,
                enabledCapabilities = enabledCapabilities,
                meterRegistry = null
            )
        )
    }

    private companion object {
        val ROUTE_IDENTITY = QueryBackendRouteIdentity("mongo-query-integration")
    }
}
