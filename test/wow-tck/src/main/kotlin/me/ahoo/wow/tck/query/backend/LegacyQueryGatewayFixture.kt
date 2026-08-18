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

package me.ahoo.wow.tck.query.backend

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryGatewayConfiguration
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyPermissions
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.schema.JacksonQuerySchemaResolver
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchemaCustomizer
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryStructureLimits
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

fun legacyQueryGateway(
    backendFactory: QueryBackendFactory,
    target: QueryTarget,
    metadata: AggregateMetadata<*, *>,
    identity: String,
    customPolicies: List<QueryPolicy> = emptyList(),
): QueryGateway = QueryGatewayFactory.create(
    QueryGatewayConfiguration(
        admission = QueryAdmission { context ->
            Mono.just(
                QueryInvocationScope(
                    trustedAuthority = QueryAuthorityView(
                        subjectId = identity,
                        tenantId = null,
                        ownerId = null,
                        spaceIds = emptySet(),
                        permissions = setOf(QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS),
                    ),
                    requestedScope = context.request.requestedScope,
                    correlationId = context.correlationId,
                )
            )
        },
        schemaResolver = JacksonQuerySchemaResolver(
            JsonSerializer.rebuild().build(),
            listOf(metadata.copy(namedAggregate = target.namedAggregate)),
            eventPayloadCustomizer(target.documentKind),
        ),
        backendResolver = object : QueryBackendResolver {
            override fun resolve(target: QueryTarget): Mono<ResolvedQueryBackend> =
                Mono.error(AssertionError("Context-only resolution is required."))

            override fun resolve(context: QueryBackendResolutionContext): Mono<ResolvedQueryBackend> =
                ResolvedQueryBackend.resolve(backendFactory.bind(context), QueryBackendRouteIdentity(identity))
        },
        customPolicies = customPolicies,
        resultPolicies = emptyList(),
        clock = Clock.systemUTC(),
        zoneId = ZoneOffset.UTC,
        structureLimits = QueryStructureLimits(32, 512, 512, 64 * 1024),
        systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
        enabledCapabilities = emptySet(),
        meterRegistry = null,
    )
)

class MandatoryTenantQueryPolicy(private val tenantId: String) : QueryPolicy {
    val calls = AtomicInteger()

    override fun evaluate(context: me.ahoo.wow.query.policy.QueryPolicyContext): Mono<QueryPolicyResult> =
        Mono.fromSupplier {
            calls.incrementAndGet()
            QueryPolicyResult(
                PredicateExpression(
                    LogicalField("tenantId"),
                    PortableOperator.EQ,
                    listOf(QueryValue.StringValue(tenantId)),
                )
            )
        }
}

private fun eventPayloadCustomizer(documentKind: QueryDocumentKind): List<QuerySchemaCustomizer> =
    if (documentKind == QueryDocumentKind.EVENT_STREAM) {
        listOf(
            QuerySchemaCustomizer { context ->
                context.baseSchema.withField(
                    QueryFieldSchema(
                        path = LogicalField("body.body"),
                        valueKind = QueryFieldValueKind.MAP,
                        nullable = false,
                        queryable = false,
                    )
                )
            }
        )
    } else {
        emptyList()
    }
