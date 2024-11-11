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

package me.ahoo.wow.elasticsearch.eventsourcing

import co.elastic.clients.transport.rest_client.RestClientTransport
import me.ahoo.wow.elasticsearch.IndexTemplateInitializer
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.tck.container.ElasticsearchLauncher
import me.ahoo.wow.tck.container.ElasticsearchLauncher.ELASTIC_PWD
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import org.junit.jupiter.api.BeforeAll
import org.springframework.data.elasticsearch.client.ClientConfiguration
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchTemplate
import org.springframework.data.elasticsearch.core.convert.ElasticsearchCustomConversions
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext
import java.time.Duration

class ElasticsearchEventStoreTest : EventStoreSpec() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun waitLauncher() {
            ElasticsearchLauncher.isRunning
        }
    }

    private fun initTemplate(elasticsearchClient: ReactiveElasticsearchClient) {
        val mappingContext = SimpleElasticsearchMappingContext()
        val converter = MappingElasticsearchConverter(mappingContext)
        converter.setConversions(ElasticsearchCustomConversions(emptyList<Any>()))
        val elasticsearchTemplate = ReactiveElasticsearchTemplate(elasticsearchClient, converter)
        IndexTemplateInitializer(elasticsearchTemplate).initEventStreamTemplate().block()
    }

    override fun createEventStore(): EventStore {
        val clientConfiguration = ClientConfiguration.builder()
            .connectedTo(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.httpHostAddress)
            .usingSsl(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.createSslContextFromCa())
            .withBasicAuth("elastic", ELASTIC_PWD)
            .withSocketTimeout(Duration.ofSeconds(30))
            .withConnectTimeout(Duration.ofSeconds(5))
            .build()
        val restClient = ElasticsearchClients.getRestClient(clientConfiguration)
        val transport = RestClientTransport(restClient, WowJsonpMapper)
        val elasticsearchClient = ReactiveElasticsearchClient(transport)
        initTemplate(elasticsearchClient)
        return ElasticsearchEventStore(
            elasticsearchClient = elasticsearchClient
        )
    }

    /**
     * TODO
     */
    override fun appendEventStreamWhenDuplicateRequestIdException() = Unit
}
