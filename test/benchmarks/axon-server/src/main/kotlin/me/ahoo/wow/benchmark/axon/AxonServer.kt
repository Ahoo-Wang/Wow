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

package me.ahoo.wow.benchmark.axon

import org.axonframework.common.caching.Cache
import org.axonframework.common.caching.WeakReferenceCache
import org.axonframework.common.jdbc.ConnectionProvider
import org.axonframework.common.jdbc.PersistenceExceptionResolver
import org.axonframework.common.transaction.TransactionManager
import org.axonframework.eventhandling.tokenstore.TokenStore
import org.axonframework.eventhandling.tokenstore.jdbc.JdbcTokenStore
import org.axonframework.eventhandling.tokenstore.jdbc.PostgresTokenTableFactory
import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition
import org.axonframework.eventsourcing.SnapshotTriggerDefinition
import org.axonframework.eventsourcing.Snapshotter
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.jdbc.JdbcEventStorageEngine
import org.axonframework.eventsourcing.eventstore.jdbc.PostgresEventTableFactory
import org.axonframework.serialization.Serializer
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@SpringBootApplication
class AxonServer

fun main(args: Array<String>) {
    runApplication<AxonServer>(*args)
}

/**
 * Axon Framework's configuration for production without Axon Server, as its reference guide recommends it: the JDBC
 * event storage engine and token store on PostgreSQL (their tables created when missing), Jackson for every message
 * and snapshot (`application.yaml`), the account's repository with a cache and a snapshot every [SNAPSHOT_THRESHOLD]
 * events, and the transfer's reactions in a tracking processor of 16 segments (`application.yaml`).
 */
@Configuration
class AxonConfiguration {
    @Bean(ACCOUNT_CACHE)
    fun accountCache(): Cache = WeakReferenceCache()

    @Bean(ACCOUNT_SNAPSHOTS)
    fun accountSnapshots(snapshotter: Snapshotter): SnapshotTriggerDefinition =
        EventCountSnapshotTriggerDefinition(snapshotter, SNAPSHOT_THRESHOLD)

    @Bean
    fun eventStorageEngine(
        connections: ConnectionProvider,
        transactions: TransactionManager,
        persistenceExceptions: PersistenceExceptionResolver,
        serializer: Serializer,
        @Qualifier("eventSerializer") eventSerializer: Serializer,
    ): EventStorageEngine = JdbcEventStorageEngine.builder()
        .connectionProvider(connections)
        .transactionManager(transactions)
        .persistenceExceptionResolver(persistenceExceptions)
        .snapshotSerializer(serializer)
        .eventSerializer(eventSerializer)
        .build()
        .also { it.createSchema(PostgresEventTableFactory.INSTANCE) }

    @Bean
    fun tokenStore(connections: ConnectionProvider, serializer: Serializer, transactions: TransactionManager): TokenStore =
        JdbcTokenStore.builder()
            .connectionProvider(connections)
            .serializer(serializer)
            .build()
            .also { store -> transactions.executeInTransaction { store.createSchema(PostgresTokenTableFactory.INSTANCE) } }

    companion object {
        const val ACCOUNT_CACHE = "accountCache"
        const val ACCOUNT_SNAPSHOTS = "accountSnapshots"
        const val SNAPSHOT_THRESHOLD = 100
    }
}
