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

package me.ahoo.wow.spring.boot.starter.elasticsearch

import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.boot.actuate.endpoint.Access
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import reactor.core.publisher.Mono
import java.time.Instant

@AutoConfiguration(after = [ElasticsearchEventSourcingAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnClass(
    name = [
        "me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver",
        "org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint",
    ],
)
@ConditionalOnBean(ElasticsearchIndexMappingResolver::class)
class ElasticsearchMappingEndpointAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun wowElasticsearchMappingEndpoint(
        mappingResolver: ElasticsearchIndexMappingResolver,
    ): WowElasticsearchMappingEndpoint = WowElasticsearchMappingEndpoint(mappingResolver)
}

@WebEndpoint(id = "wowElasticsearchMapping", defaultAccess = Access.NONE)
class WowElasticsearchMappingEndpoint(
    private val mappingResolver: ElasticsearchIndexMappingResolver,
) {
    @WriteOperation
    fun refresh(
        @Selector contextName: String,
        @Selector aggregateName: String,
    ): Mono<ElasticsearchMappingEndpointResponse> {
        val namedAggregate = MaterializedNamedAggregate(contextName, aggregateName)
        if (!MetadataSearcher.namedAggregateType.containsKey(namedAggregate)) {
            throw InvalidEndpointRequestException(
                "NamedAggregate [$namedAggregate] is not registered.",
                "UNKNOWN_AGGREGATE",
            )
        }
        val indexName = namedAggregate.toSnapshotIndexName()
        return mappingResolver.refresh(indexName).map { result ->
            ElasticsearchMappingEndpointResponse(
                indexName = indexName,
                fieldCount = result.mapping.fieldCount,
                changed = result.changed,
                refreshedAt = Instant.now(),
            )
        }
    }
}

data class ElasticsearchMappingEndpointResponse(
    val scope: String = "LOCAL_INSTANCE",
    val indexName: String,
    val fieldCount: Int,
    val changed: Boolean,
    val refreshedAt: Instant,
)
