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

package me.ahoo.wow.spring.boot.starter.mongo

import com.mongodb.reactivestreams.client.MongoClient
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.mongo.query.backend.MongoQueryBackendFactory
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnEventStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnSnapshotStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.mongodb.autoconfigure.MongoProperties as DataMongoProperties

@Configuration(proxyBeanMethods = false)
internal class MongoQueryBackendBindingConfiguration private constructor(
    private val mongoProperties: MongoProperties,
) {

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.MONGO)
    @JvmSynthetic
    fun mongoEventQueryBackendBinding(
        mongoClient: MongoClient,
        dataMongoProperties: DataMongoProperties?,
    ): QueryBackendBinding = QueryBackendBinding.storage(
        storage = StorageType.MONGO,
        documentKind = QueryDocumentKind.EVENT_STREAM,
        backendFactory = MongoQueryBackendFactory(
            mongoClient.getDatabase(
                requireNotNull(mongoProperties.eventStreamDatabase ?: dataMongoProperties?.mongoClientDatabase) {
                    "${MongoProperties.PREFIX}.event-stream-database must not be null!"
                },
            ),
        ),
    )

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.MONGO)
    @JvmSynthetic
    fun mongoSnapshotQueryBackendBinding(
        mongoClient: MongoClient,
        dataMongoProperties: DataMongoProperties?,
    ): QueryBackendBinding = QueryBackendBinding.storage(
        storage = StorageType.MONGO,
        documentKind = QueryDocumentKind.SNAPSHOT,
        backendFactory = MongoQueryBackendFactory(
            mongoClient.getDatabase(
                requireNotNull(mongoProperties.snapshotDatabase ?: dataMongoProperties?.mongoClientDatabase) {
                    "${MongoProperties.PREFIX}.snapshot-database must not be null!"
                },
            ),
        ),
    )
}
