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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.GatewayEventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.GatewaySnapshotQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryAuthority
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryCall
import me.ahoo.wow.query.gateway.QueryCallResolver
import me.ahoo.wow.query.gateway.QueryExecutionException
import me.ahoo.wow.query.gateway.QueryPurpose
import me.ahoo.wow.query.gateway.QueryRawServiceSource
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingAutoConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ResolvableType
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class QueryGatewayAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .enableWow()
        .withConfiguration(
            AutoConfigurations.of(
                QueryAutoConfiguration::class.java,
                StorageRoutingAutoConfiguration::class.java,
                QueryGatewayAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `framework managed factories and aggregate bean should use gateway facade`() {
        contextRunner
            .withUserConfiguration(TrustedQueryContextConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                context.getBean(SnapshotQueryServiceFactory::class.java)
                    .assert().isInstanceOf(GatewaySnapshotQueryServiceFactory::class.java)
                context.getBean(EventStreamQueryServiceFactory::class.java)
                    .assert().isInstanceOf(GatewayEventStreamQueryServiceFactory::class.java)
                context.getBean(QueryRawServiceSource::class.java)
                    .assert().isInstanceOf(StorageBindingQueryRawServiceRegistry::class.java)
                (context.getBean(QueryRawServiceSource::class.java) is SnapshotQueryServiceFactory)
                    .assert().isFalse()

                val aggregateBean = context.getBean(SNAPSHOT_QUERY_SERVICE_BEAN, SnapshotQueryService::class.java)
                val aggregateType = MetadataSearcher.namedAggregateType.getValue(ORDER)
                    .aggregateMetadata<Any, Any>().state.aggregateType
                val genericType = ResolvableType.forClassWithGenerics(
                    SnapshotQueryService::class.java,
                    aggregateType,
                )
                context.getBeanProvider<Any>(genericType).getObject().assert().isSameAs(aggregateBean)
                context.getBean(SnapshotQueryServiceFactory::class.java).create<Any>(ORDER)
                    .assert().isSameAs(aggregateBean)

                StepVerifier.create(aggregateBean.count(Condition.all()))
                    .expectNext(0)
                    .verifyComplete()
                StepVerifier.create(context.getBean(SnapshotQueryHandler::class.java).count(ORDER, Condition.all()))
                    .expectNext(0)
                    .verifyComplete()
            }
    }

    @Test
    fun `default facade should fail closed when trusted call context is missing`() {
        contextRunner.run { context: AssertableApplicationContext ->
            val queryService = context.getBean(SnapshotQueryServiceFactory::class.java).create<Any>(ORDER)

            StepVerifier.create(queryService.count(Condition.all()))
                .expectErrorSatisfies { error ->
                    error.assert().isInstanceOf(QueryExecutionException::class.java)
                    (error as QueryExecutionException).code.assert().isEqualTo("QUERY_CALL_REQUIRED")
                    error.path.assert().isEqualTo("$.executionContext.call")
                }
                .verify()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class TrustedQueryContextConfiguration {
        @Bean
        fun queryCallResolver(): QueryCallResolver = QueryCallResolver { request ->
            Mono.just(QueryCall(request.target, PURPOSE))
        }

        @Bean
        fun queryAuthorityResolver(): QueryAuthorityResolver = QueryAuthorityResolver {
            Mono.just(QueryAuthority.System("query-gateway-test", "Spring facade test"))
        }
    }

    private companion object {
        val ORDER = MaterializedNamedAggregate("example-service", "order")
        val PURPOSE = QueryPurpose("spring-facade-test")
        const val SNAPSHOT_QUERY_SERVICE_BEAN = "example.order.SnapshotQueryService"
    }
}
