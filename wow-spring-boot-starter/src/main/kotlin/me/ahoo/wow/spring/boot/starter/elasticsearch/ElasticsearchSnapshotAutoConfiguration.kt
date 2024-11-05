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
import me.ahoo.wow.elasticsearch.DefaultSnapshotIndexNameConverter
import me.ahoo.wow.elasticsearch.ElasticsearchSnapshotRepository
import me.ahoo.wow.elasticsearch.SnapshotIndexNameConverter
import me.ahoo.wow.elasticsearch.SnapshotJsonpMapper
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotStorage
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

@AutoConfiguration(after = [ElasticsearchRestClientAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnSnapshotEnabled
@ConditionalOnProperty(
    SnapshotProperties.STORAGE,
    havingValue = SnapshotStorage.ELASTICSEARCH_NAME,
)
class ElasticsearchSnapshotAutoConfiguration {
    @Bean
    fun snapshotIndexNameConverter(): SnapshotIndexNameConverter {
        return DefaultSnapshotIndexNameConverter
    }

    @Bean
    fun jacksonJsonpMapper(): JacksonJsonpMapper {
        return SnapshotJsonpMapper
    }

    @Bean
    fun snapshotRepository(
        elasticsearchClient: ReactiveElasticsearchClient,
        snapshotIndexNameConverter: SnapshotIndexNameConverter
    ): SnapshotRepository {
        return ElasticsearchSnapshotRepository(elasticsearchClient, snapshotIndexNameConverter)
    }

    @Bean
    fun elasticsearchSnapshotQueryServiceFactory(
        elasticsearchClient: ReactiveElasticsearchClient,
    ): SnapshotQueryServiceFactory {
        return ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient)
    }
}
