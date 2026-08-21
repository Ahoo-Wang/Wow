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

import me.ahoo.wow.query.backend.QueryRouter
import me.ahoo.wow.query.gateway.QueryLimits
import me.ahoo.wow.query.gateway.SnapshotQueryGatewayFactory
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.result.QueryResultPolicy
import me.ahoo.wow.query.schema.JacksonQuerySchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaProvider
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingAutoConfiguration
import me.ahoo.wow.spring.query.SnapshotQueryGatewayRegistrar
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper

@AutoConfiguration(after = [StorageRoutingAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnBean(QueryRouter::class)
@Import(SnapshotQueryGatewayRegistrar::class)
class SnapshotQueryGatewayAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(QuerySchemaProvider::class)
    fun querySchemaProvider(objectMapper: ObjectMapper): QuerySchemaProvider =
        JacksonQuerySchemaProvider(objectMapper)

    @Bean
    @ConditionalOnMissingBean(QueryLimits::class)
    fun queryLimits(): QueryLimits = QueryLimits()

    @Bean
    @ConditionalOnMissingBean(SnapshotQueryGatewayFactory::class)
    fun snapshotQueryGatewayFactory(
        schemaProvider: QuerySchemaProvider,
        router: QueryRouter,
        objectMapper: ObjectMapper,
        policies: List<QueryPolicy>,
        resultPolicies: List<QueryResultPolicy>,
        limits: QueryLimits
    ): SnapshotQueryGatewayFactory = SnapshotQueryGatewayFactory.create(
        schemaProvider = schemaProvider,
        router = router,
        objectMapper = objectMapper,
        policies = policies,
        resultPolicies = resultPolicies,
        limits = limits
    )
}
