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

import com.zaxxer.hikari.HikariDataSource
import org.axonframework.common.caching.Cache
import org.axonframework.common.jdbc.ConnectionProvider
import org.axonframework.common.jdbc.PersistenceExceptionResolver
import org.axonframework.common.transaction.TransactionManager
import org.axonframework.config.Configuration as AxonConfig
import org.axonframework.config.ConfigurerModule
import org.axonframework.eventhandling.PropagatingErrorHandler
import org.axonframework.eventhandling.tokenstore.TokenStore
import org.axonframework.eventhandling.tokenstore.jdbc.JdbcTokenStore
import org.axonframework.eventhandling.tokenstore.jdbc.PostgresTokenTableFactory
import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition
import org.axonframework.eventsourcing.SnapshotTriggerDefinition
import org.axonframework.eventsourcing.Snapshotter
import org.axonframework.eventsourcing.SnapshotterSpanFactory
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.jdbc.JdbcEventStorageEngine
import org.axonframework.eventsourcing.eventstore.jdbc.PostgresEventTableFactory
import org.axonframework.messaging.annotation.HandlerDefinition
import org.axonframework.messaging.annotation.ParameterResolverFactory
import org.axonframework.modelling.command.Repository
import org.axonframework.modelling.command.RepositoryProvider
import org.axonframework.serialization.Serializer
import org.axonframework.spring.eventsourcing.SpringAggregateSnapshotter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import javax.sql.DataSource

@SpringBootApplication
class AxonServer

fun main(args: Array<String>) {
    runApplication<AxonServer>(*args) {
        setDefaultProperties(mapOf(SEGMENTS to segmentsFor(Runtime.getRuntime().availableProcessors())))
    }
}

/** The property that sets the transfer processor's segments and threads (`application.yaml`); [segmentsFor] else. */
const val SEGMENTS = "benchmark.axon.segments"

private const val MAX_SEGMENTS = 16

/**
 * The transfer processor's segments and threads unless [SEGMENTS] sets them: one per CPU of the node, at most 16. On a
 * runner's two CPUs the suite axon-pg-segments found 2 cheaper than 16 in CPU and memory per command.
 */
fun segmentsFor(processors: Int): Int = minOf(MAX_SEGMENTS, processors)

/**
 * Axon Framework's configuration for production without Axon Server, as its reference guide recommends it: the JDBC
 * event storage engine and token store on PostgreSQL (their tables created when missing), Jackson for every message
 * and snapshot (`application.yaml`), the account's repository with a cache of strong references that holds every
 * account ([BoundedCache], [ACCOUNT_CACHE_CAPACITY]) and a snapshot every [SNAPSHOT_THRESHOLD] events taken on threads
 * of its own ([SNAPSHOT_THREADS]), not on the thread that committed, and the transfer's reactions in a tracking
 * processor of a segment per CPU ([segmentsFor]), retried when they fail ([transferErrors]).
 */
@Configuration
class AxonConfiguration {
    // The snapshots taken and the threads they ran on, which the node log reports (axonStats).
    private val snapshots = LongAdder()
    private val snapshotThreads: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val snapshotThreadIndex = AtomicInteger()
    private val snapshotPool = Executors.newFixedThreadPool(SNAPSHOT_THREADS) { task ->
        Thread(task, "snapshotter-${snapshotThreadIndex.incrementAndGet()}").apply { isDaemon = true }
    }
    private val snapshotExecutor = Executor { task ->
        snapshotPool.execute {
            snapshotThreads += Thread.currentThread().name
            snapshots.increment()
            task.run()
        }
    }

    @Bean(ACCOUNT_CACHE)
    fun accountCache(): BoundedCache = BoundedCache(ACCOUNT_CACHE_CAPACITY)

    /**
     * The transfer's reactions retried, not skipped, when one fails (a lock or a connection that timed out, a handler
     * interrupted at its timeout): Axon's default `LoggingErrorHandler` logs the failure and goes on with the next
     * event, so the transfer stops half done (an entry never confirmed). With `PropagatingErrorHandler` the failure
     * rolls the processor's unit of work back, and the tracking processor releases its segment and retries the event
     * after a pause, doubled at each failure up to 60 s.
     */
    @Bean
    fun transferErrors(): ConfigurerModule = ConfigurerModule { configurer ->
        configurer.eventProcessing()
            .registerListenerInvocationErrorHandler(TransferReactions.GROUP) { PropagatingErrorHandler.instance() }
    }

    /**
     * What a run checks of Axon's side from the node log, every [STATS_SECONDS]: the account cache's entries, hits and
     * misses; the snapshots taken and the threads they ran on; the connection pool's connections in use and idle and
     * the threads waiting for one; the routes' commands in flight ([CommandsInFlight]) and those queued for a thread
     * ([CommandSenders]). `axon-stats` lines at INFO (`application.yaml`).
     */
    @Bean(destroyMethod = "shutdownNow")
    fun axonStats(
        cache: BoundedCache,
        dataSource: DataSource,
        inFlight: CommandsInFlight,
        senders: CommandSenders,
    ): ScheduledExecutorService {
        val hikari = dataSource.takeIf { it.isWrapperFor(HikariDataSource::class.java) }
            ?.unwrap(HikariDataSource::class.java)
        return Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "axon-stats").apply { isDaemon = true } }
            .also { stats ->
                stats.scheduleAtFixedRate(
                    {
                        val pool = hikari?.hikariPoolMXBean
                        log.info(
                            "axon-stats cache entries={} hits={} misses={}; snapshots={} on {}; " +
                                "pool active={} idle={} waiting={}; commands in flight={} queued={}",
                            cache.size,
                            cache.hits,
                            cache.misses,
                            snapshots.sum(),
                            snapshotThreads.sorted(),
                            pool?.activeConnections,
                            pool?.idleConnections,
                            pool?.threadsAwaitingConnection,
                            inFlight.current,
                            senders.queued,
                        )
                    },
                    STATS_SECONDS,
                    STATS_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
    }

    /**
     * The snapshotter of Axon's Spring Boot configuration, with an executor: Axon's takes each snapshot on the thread
     * whose commit triggered it, the command's, and its reference guide recommends an executor of its own in production.
     */
    @Bean
    fun aggregateSnapshotter(
        configuration: AxonConfig,
        handlerDefinition: HandlerDefinition,
        parameterResolverFactory: ParameterResolverFactory,
        eventStore: EventStore,
        transactionManager: TransactionManager,
        spanFactory: SnapshotterSpanFactory,
    ): SpringAggregateSnapshotter = SpringAggregateSnapshotter.builder()
        .repositoryProvider(
            object : RepositoryProvider {
                override fun <T> repositoryFor(aggregateType: Class<T>): Repository<T> =
                    configuration.repository(aggregateType)
            },
        )
        .transactionManager(transactionManager)
        .eventStore(eventStore)
        .parameterResolverFactory(parameterResolverFactory)
        .handlerDefinition(handlerDefinition)
        .spanFactory(spanFactory)
        .executor(snapshotExecutor)
        .build()

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

        /** Every account the benchmark seeds (100,000 at most), with room to spare. */
        const val ACCOUNT_CACHE_CAPACITY = 131_072

        /** As many as the node's CPUs on a benchmark runner. */
        const val SNAPSHOT_THREADS = 2

        const val STATS_SECONDS = 10L

        private val log = LoggerFactory.getLogger(AxonConfiguration::class.java)
    }
}
