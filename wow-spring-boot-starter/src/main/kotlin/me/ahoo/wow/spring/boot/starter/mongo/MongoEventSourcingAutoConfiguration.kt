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
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.mongo.EventStreamSchemaInitializer
import me.ahoo.wow.mongo.MongoDatabaseContextGuard
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.SnapshotSchemaInitializer
import me.ahoo.wow.mongo.prepare.MongoPrepareKeyFactory
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceFactory
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceFactory
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnEventStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnSnapshotStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStreamQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.prepare.ConditionalOnPrepareEnabled
import me.ahoo.wow.spring.boot.starter.prepare.PrepareProperties
import me.ahoo.wow.spring.boot.starter.prepare.PrepareStorage
import me.ahoo.wow.spring.boot.starter.query.QueryProperties
import me.ahoo.wow.spring.boot.starter.query.QuerySchemaAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration(
    after = [
        WowAutoConfiguration::class,
        MongoReactiveAutoConfiguration::class,
        QuerySchemaAutoConfiguration::class,
    ],
)
@ConditionalOnWowEnabled
@ConditionalOnMongoEnabled
@ConditionalOnClass(MongoEventStore::class)
@EnableConfigurationProperties(
    MongoProperties::class,
    MongoEventStoreBatchProperties::class,
    MongoSnapshotStoreBatchProperties::class,
)
class MongoEventSourcingAutoConfiguration(
    private val mongoProperties: MongoProperties,
    private val eventStoreBatchProperties: MongoEventStoreBatchProperties,
    private val snapshotStoreBatchProperties: MongoSnapshotStoreBatchProperties,
) {

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.MONGO)
    fun mongoEventStore(
        mongoClient: MongoClient,
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?,
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        currentBoundedContext: NamedBoundedContext,
        metrics: ObjectProvider<WowMetrics>,
    ): MongoEventStore {
        val eventStoreDatabase = getEventStreamDatabase(dataMongoProperties, mongoClient)
        MongoDatabaseContextGuard(eventStoreDatabase)
            .ensureContext(currentBoundedContext.contextName)
        if (mongoProperties.autoInitSchema) {
            EventStreamSchemaInitializer(eventStoreDatabase).initAll()
        }
        return MongoEventStore(
            database = eventStoreDatabase,
            batchOptions = eventStoreBatchProperties.toOptions(),
            metrics = metrics.getIfAvailable { WowMetrics.NONE },
        )
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.MONGO)
    fun mongoEventStoreBinding(
        @Qualifier("mongoEventStore")
        eventStore: EventStore
    ): EventStoreBinding {
        return EventStoreBinding.storage(StorageType.MONGO, eventStore)
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.MONGO)
    fun mongoEventStreamQueryServiceFactory(
        mongoClient: MongoClient,
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?,
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        currentBoundedContext: NamedBoundedContext,
        sources: List<QuerySchemaSource> = emptyList(),
        queryProperties: QueryProperties = QueryProperties(),
    ): MongoEventStreamQueryServiceFactory {
        val eventStoreDatabase = getEventStreamDatabase(dataMongoProperties, mongoClient)
        MongoDatabaseContextGuard(eventStoreDatabase)
            .ensureContext(currentBoundedContext.contextName)
        return MongoEventStreamQueryServiceFactory(
            eventStoreDatabase,
            sources,
            queryProperties.schema.validationMode,
        )
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.MONGO)
    fun mongoEventStreamQueryServiceFactoryBinding(
        mongoEventStreamQueryServiceFactory: MongoEventStreamQueryServiceFactory
    ): EventStreamQueryServiceFactoryBinding {
        return EventStreamQueryServiceFactoryBinding.storage(StorageType.MONGO, mongoEventStreamQueryServiceFactory)
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

    @Bean(name = ["mongoSnapshotStore", "mongoSnapshotRepository"])
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.MONGO)
    fun mongoSnapshotStore(
        mongoClient: MongoClient,
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?,
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        currentBoundedContext: NamedBoundedContext,
        metrics: ObjectProvider<WowMetrics>,
    ): MongoSnapshotStore {
        val snapshotDatabase = getMongoSnapshotDatabase(dataMongoProperties, mongoClient)
        MongoDatabaseContextGuard(snapshotDatabase)
            .ensureContext(currentBoundedContext.contextName)
        if (mongoProperties.autoInitSchema) {
            SnapshotSchemaInitializer(snapshotDatabase).initAll()
        }
        return MongoSnapshotStore(
            database = snapshotDatabase,
            batchOptions = snapshotStoreBatchProperties.toOptions(),
            metrics = metrics.getIfAvailable { WowMetrics.NONE },
        )
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.MONGO)
    fun mongoSnapshotStoreBinding(
        @Qualifier("mongoSnapshotStore")
        snapshotStore: SnapshotStore
    ): SnapshotStoreBinding {
        return SnapshotStoreBinding.storage(StorageType.MONGO, snapshotStore)
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.MONGO)
    fun mongoSnapshotQueryServiceFactory(
        mongoClient: MongoClient,
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?,
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        currentBoundedContext: NamedBoundedContext,
        sources: List<QuerySchemaSource>,
        queryProperties: QueryProperties,
    ): MongoSnapshotQueryServiceFactory {
        val snapshotDatabase = getMongoSnapshotDatabase(dataMongoProperties, mongoClient)
        MongoDatabaseContextGuard(snapshotDatabase)
            .ensureContext(currentBoundedContext.contextName)
        return MongoSnapshotQueryServiceFactory(
            database = snapshotDatabase,
            schemaSources = sources,
            validationMode = queryProperties.schema.validationMode,
        )
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.MONGO)
    fun mongoSnapshotQueryServiceFactoryBinding(
        mongoSnapshotQueryServiceFactory: MongoSnapshotQueryServiceFactory
    ): SnapshotQueryServiceFactoryBinding {
        return SnapshotQueryServiceFactoryBinding.storage(StorageType.MONGO, mongoSnapshotQueryServiceFactory)
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
        dataMongoProperties: org.springframework.boot.mongodb.autoconfigure.MongoProperties?,
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        currentBoundedContext: NamedBoundedContext,
    ): PrepareKeyFactory {
        val prepareDatabaseName = mongoProperties.prepareDatabase ?: dataMongoProperties?.mongoClientDatabase
        requireNotNull(prepareDatabaseName) {
            "${MongoProperties.PREFIX}.prepare-database must not be null!"
        }
        val prepareDatabase = mongoClient.getDatabase(prepareDatabaseName)
        MongoDatabaseContextGuard(prepareDatabase)
            .ensureContext(currentBoundedContext.contextName)
        return MongoPrepareKeyFactory(prepareDatabase)
    }
}
