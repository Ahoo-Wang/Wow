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

package me.ahoo.wow.spring.boot.starter.redis

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.redis.eventsourcing.RedisEventStore
import me.ahoo.wow.redis.eventsourcing.RedisSnapshotStore
import me.ahoo.wow.redis.prepare.RedisPrepareKeyFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnEventStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnSnapshotStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRouteResolver
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.prepare.ConditionalOnPrepareEnabled
import me.ahoo.wow.spring.boot.starter.prepare.PrepareProperties
import me.ahoo.wow.spring.boot.starter.prepare.PrepareStorage
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

@AutoConfiguration(after = [WowAutoConfiguration::class, DataRedisReactiveAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnRedisEnabled
@ConditionalOnClass(RedisEventStore::class)
@EnableConfigurationProperties(
    RedisProperties::class,
    EventStoreProperties::class,
    StorageRoutingProperties::class,
)
class RedisEventSourcingAutoConfiguration {

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.REDIS)
    fun redisEventStore(redisTemplate: ReactiveStringRedisTemplate): RedisEventStore {
        return RedisEventStore(redisTemplate)
    }

    @Bean
    @ConditionalOnEventStoreStorage(StorageType.REDIS)
    fun redisEventStoreBinding(
        @Qualifier("redisEventStore")
        eventStore: EventStore
    ): EventStoreBinding {
        return EventStoreBinding.storage(StorageType.REDIS, eventStore)
    }

    @Bean("redisEventStoreLayoutGuard")
    @ConditionalOnEventStoreStorage(StorageType.REDIS)
    internal fun redisEventStoreLayoutGuard(
        redisTemplate: ReactiveStringRedisTemplate,
        @Qualifier("redisEventStore") redisEventStore: EventStore,
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT) currentContext: NamedBoundedContext,
        eventStoreProperties: EventStoreProperties,
        storageRoutingProperties: StorageRoutingProperties,
        eventStoreBindings: ObjectProvider<EventStoreBinding>,
    ): SmartInitializingSingleton {
        return RedisEventStoreLayoutGuard(
            redisEventStore = redisEventStore,
            contextName = currentContext.contextName,
            defaultEventStorage = eventStoreProperties.storage,
            storageRoutingProperties = storageRoutingProperties,
            eventStoreBindingsProvider = { eventStoreBindings.orderedStream().toList() },
            detector = RedisEventStoreLayoutDetector(redisTemplate),
        )
    }

    @Bean("redisSnapshotStore")
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.REDIS)
    fun redisSnapshotStore(redisTemplate: ReactiveStringRedisTemplate): RedisSnapshotStore {
        return RedisSnapshotStore(redisTemplate)
    }

    @Bean
    @ConditionalOnSnapshotEnabled
    @ConditionalOnSnapshotStoreStorage(StorageType.REDIS)
    fun redisSnapshotStoreBinding(
        @Qualifier("redisSnapshotStore")
        snapshotStore: SnapshotStore
    ): SnapshotStoreBinding {
        return SnapshotStoreBinding.storage(StorageType.REDIS, snapshotStore)
    }

    @Bean
    @ConditionalOnProperty(
        PrepareProperties.STORAGE,
        havingValue = PrepareStorage.REDIS_NAME,
    )
    @ConditionalOnPrepareEnabled
    @ConditionalOnMissingBean
    fun redisPrepareKeyFactory(redisTemplate: ReactiveStringRedisTemplate): PrepareKeyFactory {
        return RedisPrepareKeyFactory(redisTemplate)
    }

    private enum class IncompatibleRedisEventStoreLayout {
        SHARED_REQUEST_INDEX,
        BUCKETED_AGGREGATE_ID_INDEX,
    }

    private data class RedisEventStoreLayoutDetection(
        val namedAggregate: NamedAggregate,
        val layout: IncompatibleRedisEventStoreLayout,
        val key: String,
    )

    private class RedisEventStoreLayoutDetector(
        private val redisTemplate: ReactiveStringRedisTemplate,
    ) {
        fun detect(
            namedAggregates: Set<NamedAggregate>,
        ): Flux<RedisEventStoreLayoutDetection> {
            return Flux.fromIterable(namedAggregates)
                .flatMapIterable(::candidates)
                .flatMap(
                    { candidate ->
                        redisTemplate.hasKey(candidate.key)
                            .switchIfEmpty(
                                Mono.error(
                                    IllegalStateException(
                                        "Redis EventStore layout check returned no result for key:[${candidate.key}]."
                                    )
                                )
                            )
                            .filter { exists -> exists }
                            .map { candidate }
                    },
                    DEFAULT_CONCURRENCY,
                )
        }

        private fun candidates(namedAggregate: NamedAggregate): List<RedisEventStoreLayoutDetection> =
            buildList {
                val legacyPrefix = "${namedAggregate.toStringWithAlias()}:es"
                add(
                    RedisEventStoreLayoutDetection(
                        namedAggregate = namedAggregate,
                        layout = IncompatibleRedisEventStoreLayout.SHARED_REQUEST_INDEX,
                        key = "$legacyPrefix:req_idx",
                    ),
                )
                repeat(PUBLISHED_AGGREGATE_ID_INDEX_BUCKETS) { bucket ->
                    add(
                        RedisEventStoreLayoutDetection(
                            namedAggregate = namedAggregate,
                            layout = IncompatibleRedisEventStoreLayout.BUCKETED_AGGREGATE_ID_INDEX,
                            key = "{$legacyPrefix:$bucket}:ids",
                        ),
                    )
                }
            }

        companion object {
            private const val DEFAULT_CONCURRENCY = 16
            private const val PUBLISHED_AGGREGATE_ID_INDEX_BUCKETS = 128
        }
    }

    private class RedisEventStoreLayoutGuard(
        private val redisEventStore: EventStore,
        private val contextName: String,
        private val defaultEventStorage: StorageType,
        private val storageRoutingProperties: StorageRoutingProperties,
        private val eventStoreBindingsProvider: () -> List<EventStoreBinding>,
        private val detector: RedisEventStoreLayoutDetector,
    ) : SmartInitializingSingleton {
        override fun afterSingletonsInstantiated() {
            val redisAggregates = resolveRedisAggregates()
            if (redisAggregates.isEmpty()) {
                return
            }
            val detection = detector.detect(redisAggregates)
                .next()
                .timeout(DEFAULT_TIMEOUT)
                .onErrorMap { error ->
                    IllegalStateException(
                        "Redis EventStore layout check failed closed for [${redisAggregates.size}] aggregate(s) " +
                            "within [$DEFAULT_TIMEOUT]. Startup cannot safely continue.",
                        error,
                    )
                }.block()
            detection?.let(::failOnIncompatibleLayout)
        }

        private fun resolveRedisAggregates(): Set<NamedAggregate> {
            val originalRedisEventStore = redisEventStore.getOriginalDelegate()
            val resolvedRoutes = StorageRouteResolver(
                contextName = contextName,
                snapshotEnabled = false,
                eventStoreBindings = eventStoreBindingsProvider(),
                snapshotStoreBindings = emptyList(),
                defaultEventStorage = defaultEventStorage,
            ).resolveEventRoutes(storageRoutingProperties)
            return MetadataSearcher.localAggregates
                .filterTo(linkedSetOf()) { namedAggregate ->
                    val selectedEventStore = resolvedRoutes.eventRoutes[namedAggregate]
                        ?: resolvedRoutes.defaultEventStore
                    selectedEventStore.getOriginalDelegate() === originalRedisEventStore
                }
        }

        private fun failOnIncompatibleLayout(detection: RedisEventStoreLayoutDetection): Nothing {
            error(
                "Incompatible Redis EventStore layout [${detection.layout}] was detected for aggregate " +
                    "[${detection.namedAggregate.contextName}.${detection.namedAggregate.aggregateName}] at key " +
                    "[${detection.key}]. This runtime supports canonical v2 only and will neither read nor migrate " +
                    "incompatible data. Stop deployment and migrate or rebuild the Redis data into canonical v2 keys, " +
                    "or configure an empty dedicated Redis database.",
            )
        }

        companion object {
            private val DEFAULT_TIMEOUT = Duration.ofSeconds(30)
        }
    }
}
