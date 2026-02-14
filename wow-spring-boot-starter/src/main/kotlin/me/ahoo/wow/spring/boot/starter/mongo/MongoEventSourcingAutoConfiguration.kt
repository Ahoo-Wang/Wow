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
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import me.ahoo.wow.mongo.EventStreamSchemaInitializer
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.MongoSnapshotRepository
import me.ahoo.wow.mongo.SnapshotSchemaInitializer
import me.ahoo.wow.mongo.prepare.MongoPrepareKeyFactory
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceFactory
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceFactory
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.prepare.ConditionalOnPrepareEnabled
import me.ahoo.wow.spring.boot.starter.prepare.PrepareProperties
import me.ahoo.wow.spring.boot.starter.prepare.PrepareStorage
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [MongoReactiveAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnMongoEnabled
@ConditionalOnClass(MongoEventStore::class)
@EnableConfigurationProperties(MongoProperties::class)
class MongoEventSourcingAutoConfiguration(private val mongoProperties: MongoProperties) {

    @Bean
    @ConditionalOnProperty(
        EventStoreProperties.STORAGE,
        matchIfMissing = true,
        havingValue = StorageType.MONGO_NAME,
    )
    fun mongoEventStore(
        mongoClient: MongoClient,
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?
    ): EventStore {
        val eventStoreDatabase = getEventStreamDatabase(dataMongoProperties, mongoClient)
        if (mongoProperties.autoInitSchema) {
            EventStreamSchemaInitializer(eventStoreDatabase).initAll()
        }
        return MongoEventStore(eventStoreDatabase)
    }

    @Bean
    @ConditionalOnProperty(
        EventStoreProperties.STORAGE,
        matchIfMissing = true,
        havingValue = StorageType.MONGO_NAME,
    )
    fun mongoEventStreamQueryServiceFactory(
        mongoClient: MongoClient,
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?
    ): EventStreamQueryServiceFactory {
        val eventStoreDatabase = getEventStreamDatabase(dataMongoProperties, mongoClient)
        return MongoEventStreamQueryServiceFactory(eventStoreDatabase)
    }

    private fun getEventStreamDatabase(
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?,
        mongoClient: MongoClient
    ): MongoDatabase {
        val eventStoreDatabaseName = mongoProperties.eventStreamDatabase ?: dataMongoProperties?.mongoClientDatabase
        requireNotNull(eventStoreDatabaseName) {
            "${MongoProperties.PREFIX}.event-stream-database must not be null!"
        }
        val eventStoreDatabase = mongoClient.getDatabase(eventStoreDatabaseName)
        return eventStoreDatabase
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnProperty(
        SnapshotProperties.STORAGE,
        matchIfMissing = true,
        havingValue = StorageType.MONGO_NAME,
    )
    fun mongoSnapshotRepository(
        mongoClient: MongoClient,
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?
    ): SnapshotRepository {
        val snapshotDatabase = getMongoSnapshotDatabase(dataMongoProperties, mongoClient)
        if (mongoProperties.autoInitSchema) {
            SnapshotSchemaInitializer(snapshotDatabase).initAll()
        }
        return MongoSnapshotRepository(snapshotDatabase)
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnProperty(
        SnapshotProperties.STORAGE,
        matchIfMissing = true,
        havingValue = StorageType.MONGO_NAME,
    )
    fun mongoSnapshotQueryServiceFactory(
        mongoClient: MongoClient,
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?
    ): SnapshotQueryServiceFactory {
        val snapshotDatabase = getMongoSnapshotDatabase(dataMongoProperties, mongoClient)
        return MongoSnapshotQueryServiceFactory(snapshotDatabase)
    }

    private fun getMongoSnapshotDatabase(
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?,
        mongoClient: MongoClient
    ): MongoDatabase {
        val snapshotDatabaseName = mongoProperties.snapshotDatabase ?: dataMongoProperties?.mongoClientDatabase
        requireNotNull(snapshotDatabaseName) {
            "${MongoProperties.PREFIX}.snapshot-database must not be null!"
        }
        val snapshotDatabase = mongoClient.getDatabase(snapshotDatabaseName)
        return snapshotDatabase
    }

    @Bean
    @ConditionalOnBean(MongoClient::class)
    @ConditionalOnProperty(
        PrepareProperties.STORAGE,
        matchIfMissing = true,
        havingValue = PrepareStorage.MONGO_NAME,
    )
    @ConditionalOnPrepareEnabled
    @ConditionalOnMissingBean
    fun mongoPrepareKeyFactory(
        mongoClient: MongoClient,
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?
    ): PrepareKeyFactory {
        val prepareDatabaseName = mongoProperties.prepareDatabase ?: dataMongoProperties?.mongoClientDatabase
        requireNotNull(prepareDatabaseName) {
            "${MongoProperties.PREFIX}.prepare-database must not be null!"
        }
        val prepareDatabase = mongoClient.getDatabase(prepareDatabaseName)
        return MongoPrepareKeyFactory(prepareDatabase)
    }
}
