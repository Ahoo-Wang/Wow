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
package me.ahoo.wow.spring.boot.starter.query

import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryGatewayConfiguration
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAuthorityProvider
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.result.ResultPolicy
import me.ahoo.wow.query.schema.JacksonQuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaCustomizer
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryStructureLimits
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneId

@AutoConfiguration(after = [QueryAutoConfiguration::class])
@ConditionalOnWowEnabled
@EnableConfigurationProperties(QueryGatewayProperties::class)
class QueryGatewayAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(QueryAuthorityProvider::class)
    fun queryAuthorityProvider(): QueryAuthorityProvider = ANONYMOUS_AUTHORITY_PROVIDER

    @Bean
    @ConditionalOnMissingBean(QuerySchemaResolver::class)
    fun querySchemaResolver(
        objectMapper: ObjectMapper,
        beanFactory: ListableBeanFactory
    ): QuerySchemaResolver {
        val metadata = MetadataSearcher.namedAggregateType.values
            .map { aggregateType -> aggregateType.aggregateMetadata<Any, Any>() }
        val customizers = beanFactory.getBeanProvider(QuerySchemaCustomizer::class.java)
            .orderedStream()
            .toList()
        return JacksonQuerySchemaResolver(objectMapper, metadata, customizers)
    }

    @Bean
    @ConditionalOnMissingBean(QueryGateway::class)
    fun queryGateway(
        properties: QueryGatewayProperties,
        schemaResolver: QuerySchemaResolver,
        authorityProvider: QueryAuthorityProvider,
        backendResolver: ObjectProvider<QueryBackendResolver>,
        clockProvider: ObjectProvider<Clock>,
        zoneIdProvider: ObjectProvider<ZoneId>,
        meterRegistry: ObjectProvider<MeterRegistry>,
        beanFactory: ListableBeanFactory
    ): QueryGateway {
        val clock = clockProvider.getIfAvailable { Clock.systemUTC() }
        val zoneId = zoneIdProvider.getIfAvailable { clock.zone }
        return QueryGatewayFactory.create(
            QueryGatewayConfiguration(
                admission = authorityAdmission(authorityProvider),
                schemaResolver = schemaResolver,
                backendResolver = backendResolver.getIfAvailable { UNAVAILABLE_BACKEND_RESOLVER },
                customPolicies = beanFactory.orderedSnapshot(QueryPolicy::class.java),
                resultPolicies = beanFactory.orderedSnapshot(ResultPolicy::class.java),
                clock = clock,
                zoneId = zoneId,
                structureLimits = properties.toStructureLimits(),
                systemBudgetLimit = properties.systemBudget.toBudgetLimit(),
                enabledCapabilities = properties.enabledCapabilities.mapTo(LinkedHashSet(), ::QueryCapabilityId),
                meterRegistry = meterRegistry.getIfAvailable()
            )
        )
    }

    private fun authorityAdmission(authorityProvider: QueryAuthorityProvider): QueryAdmission = QueryAdmission { context ->
        authorityProvider.getAuthority(context).map { authority ->
            QueryInvocationScope(
                trustedAuthority = authority,
                requestedScope = context.request.requestedScope,
                correlationId = context.correlationId
            )
        }
    }

    private fun QueryGatewayProperties.toStructureLimits(): QueryStructureLimits = QueryStructureLimits(
        maxDepth = maxDepth,
        maxNodes = maxNodes,
        maxMembershipItems = maxMembershipItems,
        maxNativeParameterBytes = maxNativeParameterBytes
    )

    private fun QueryGatewaySystemBudgetProperties.toBudgetLimit(): QueryBudgetLimit = QueryBudgetLimit(
        timeout = timeout,
        maxResults = maxResults,
        maxCost = maxCost
    )

    private fun <T : Any> ListableBeanFactory.orderedSnapshot(type: Class<T>): List<T> =
        getBeanProvider(type).orderedStream().toList()

    private companion object {
        val ANONYMOUS_AUTHORITY_PROVIDER: QueryAuthorityProvider = QueryAuthorityProvider {
            Mono.just(QueryAuthorityView(null, null, null, emptySet(), emptySet()))
        }
        val UNAVAILABLE_BACKEND_RESOLVER: QueryBackendResolver = QueryBackendResolver {
            Mono.error(
                QueryException(
                    QueryErrorCode.BACKEND_NOT_READY,
                    QueryStage.BACKEND_RESOLUTION,
                    QueryErrorReason.BACKEND_UNAVAILABLE
                )
            )
        }
    }
}
