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

package me.ahoo.wow.elasticsearch.query

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryBackendFactory
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryGatewayConfiguration
import me.ahoo.wow.query.QueryGatewayFactory
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
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

internal fun legacyElasticsearchQueryGateway(
    client: ReactiveElasticsearchClient,
    target: QueryTarget,
    metadata: AggregateMetadata<*, *>,
    customPolicies: List<QueryPolicy> = emptyList()
): QueryGateway {
    val backendFactory = ElasticsearchQueryBackendFactory(client)
    val schemaResolver = JacksonQuerySchemaResolver(
        JsonSerializer.rebuild().build(),
        listOf(metadata.copy(namedAggregate = target.namedAggregate)),
        eventPayloadCustomizer(target.documentKind)
    )
    return QueryGatewayFactory.create(
        QueryGatewayConfiguration(
            admission = QueryAdmission { context ->
                Mono.just(
                    QueryInvocationScope(
                        trustedAuthority = QueryAuthorityView(
                            subjectId = "legacy-elasticsearch-tck",
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
            schemaResolver = schemaResolver,
            backendResolver = object : QueryBackendResolver {
                override fun resolve(target: QueryTarget) =
                    Mono.error<ResolvedQueryBackend>(AssertionError("Context-only resolution is required."))

                override fun resolve(context: me.ahoo.wow.query.backend.QueryBackendResolutionContext) =
                    ResolvedQueryBackend.resolve(backendFactory.bind(context), ROUTE_IDENTITY)
            },
            customPolicies = customPolicies,
            resultPolicies = emptyList(),
            clock = Clock.systemUTC(),
            zoneId = ZoneOffset.UTC,
            structureLimits = QueryStructureLimits(32, 512, 512, 64 * 1024),
            systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
            enabledCapabilities = emptySet(),
            meterRegistry = null
        )
    )
}

private fun eventPayloadCustomizer(documentKind: QueryDocumentKind): List<QuerySchemaCustomizer> =
    if (documentKind != QueryDocumentKind.EVENT_STREAM) {
        emptyList()
    } else {
        listOf(
            QuerySchemaCustomizer { context ->
                context.baseSchema.withField(
                    QueryFieldSchema(
                        path = LogicalField("body.body"),
                        valueKind = QueryFieldValueKind.MAP,
                        nullable = false,
                        queryable = false
                    )
                )
            }
        )
    }

private val ROUTE_IDENTITY = QueryBackendRouteIdentity("legacy-elasticsearch-tck")

internal class ElasticsearchMandatoryTenantPolicy(private val tenantId: String) : QueryPolicy {
    val calls = AtomicInteger()

    override fun evaluate(context: me.ahoo.wow.query.policy.QueryPolicyContext): Mono<QueryPolicyResult> =
        Mono.fromSupplier {
            calls.incrementAndGet()
            QueryPolicyResult(
                PredicateExpression(
                    LogicalField("tenantId"),
                    PortableOperator.EQ,
                    listOf(QueryValue.StringValue(tenantId))
                )
            )
        }
}
