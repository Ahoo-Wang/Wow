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

package me.ahoo.wow.elasticsearch.query.snapshot

import co.elastic.clients.transport.rest_client.RestClientTransport
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.tck.container.ElasticsearchLauncher
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.springframework.data.elasticsearch.client.ClientConfiguration
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchSnapshotQueryServiceTest : SnapshotQueryServiceSpec() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun waitLauncher() {
            ElasticsearchLauncher.isRunning
        }
    }

    lateinit var elasticsearchClient: ReactiveElasticsearchClient

    @BeforeEach
    override fun setup() {
        val clientConfiguration = ClientConfiguration.builder()
            .connectedTo(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.httpHostAddress)
            .usingSsl(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.createSslContextFromCa())
            .withBasicAuth("elastic", ElasticsearchLauncher.ELASTIC_PWD)
            .build()
        val restClient = ElasticsearchClients.getRestClient(clientConfiguration)
        val transport = RestClientTransport(restClient, WowJsonpMapper)
        elasticsearchClient = ReactiveElasticsearchClient(transport)
        elasticsearchClient.initSnapshotTemplate()
        super.setup()
    }

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient)
    }

    override fun createSnapshotRepository(): SnapshotRepository {
        return ElasticsearchSnapshotRepository(elasticsearchClient)
    }
}
