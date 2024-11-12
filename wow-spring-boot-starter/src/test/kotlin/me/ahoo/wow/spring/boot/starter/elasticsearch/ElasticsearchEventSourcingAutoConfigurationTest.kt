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

import co.elastic.clients.json.jackson.JacksonJsonpMapper
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotRepository
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

internal class ElasticsearchEventSourcingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues("${SnapshotProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}")
            .withPropertyValues("${EventStoreProperties.STORAGE}=${StorageType.ELASTICSEARCH_NAME}")
            .withBean(ReactiveElasticsearchClient::class.java, {
                mock(ReactiveElasticsearchClient::class.java)
            })
            .withUserConfiguration(
                ElasticsearchEventSourcingAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(JacksonJsonpMapper::class.java)
                    .hasSingleBean(ElasticsearchEventStore::class.java)
                    .hasSingleBean(ElasticsearchEventStreamQueryServiceFactory::class.java)
                    .hasSingleBean(ElasticsearchSnapshotRepository::class.java)
                    .hasSingleBean(ElasticsearchSnapshotQueryServiceFactory::class.java)
            }
    }
}
