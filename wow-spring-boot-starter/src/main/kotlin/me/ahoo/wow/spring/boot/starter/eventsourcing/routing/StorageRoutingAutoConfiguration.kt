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
package me.ahoo.wow.spring.boot.starter.eventsourcing.routing

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.AggregateEventStoreRegistry
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.RoutingEventStore
import me.ahoo.wow.eventsourcing.snapshot.AggregateSnapshotStoreRegistry
import me.ahoo.wow.eventsourcing.snapshot.RoutingSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchEventSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.mock.MockEventStoreAutoConfiguration
import me.ahoo.wow.spring.boot.starter.mock.MockSnapshotAutoConfiguration
import me.ahoo.wow.spring.boot.starter.mongo.MongoEventSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration
import me.ahoo.wow.spring.boot.starter.r2dbc.R2dbcAutoConfiguration
import me.ahoo.wow.spring.boot.starter.redis.RedisEventSourcingAutoConfiguration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Primary
import org.springframework.core.type.AnnotatedTypeMetadata

@AutoConfiguration(
    after = [
        EventSourcingAutoConfiguration::class,
        EventStoreAutoConfiguration::class,
        SnapshotAutoConfiguration::class,
        MongoEventSourcingAutoConfiguration::class,
        RedisEventSourcingAutoConfiguration::class,
        R2dbcAutoConfiguration::class,
        ElasticsearchEventSourcingAutoConfiguration::class,
        MockEventStoreAutoConfiguration::class,
        MockSnapshotAutoConfiguration::class,
        QueryAutoConfiguration::class,
    ],
)
@ConditionalOnWowEnabled
@EnableConfigurationProperties(
    StorageRoutingProperties::class,
    EventStoreProperties::class,
    SnapshotProperties::class,
)
class StorageRoutingAutoConfiguration {

    @Bean
    @Primary
    @Conditional(OnEventStorageRouteCondition::class)
    fun routingEventStore(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        eventStoreProperties: EventStoreProperties,
        snapshotProperties: SnapshotProperties,
        storageRoutingProperties: StorageRoutingProperties,
        eventStoreBindings: List<EventStoreBinding>,
        snapshotStoreBindings: List<SnapshotStoreBinding>,
        eventStreamQueryServiceFactoryBindings: List<EventStreamQueryServiceFactoryBinding>,
        snapshotQueryServiceFactoryBindings: List<SnapshotQueryServiceFactoryBinding>,
    ): EventStore {
        val resolvedRoutes = StorageRouteResolver(
            contextName = namedBoundedContext.contextName,
            snapshotEnabled = snapshotProperties.enabled,
            eventStoreBindings = eventStoreBindings,
            snapshotStoreBindings = snapshotStoreBindings,
            eventStreamQueryServiceFactoryBindings = eventStreamQueryServiceFactoryBindings,
            snapshotQueryServiceFactoryBindings = snapshotQueryServiceFactoryBindings,
            defaultEventStorage = eventStoreProperties.storage,
            defaultSnapshotStorage = snapshotProperties.storage,
        ).resolveEventRoutes(storageRoutingProperties)
        return RoutingEventStore(
            AggregateEventStoreRegistry(
                defaultEventStore = resolvedRoutes.defaultEventStore,
                routes = resolvedRoutes.eventRoutes,
            ),
        )
    }

    @Bean
    @Primary
    @Conditional(OnSnapshotStorageRouteCondition::class)
    fun routingSnapshotStore(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        eventStoreProperties: EventStoreProperties,
        snapshotProperties: SnapshotProperties,
        storageRoutingProperties: StorageRoutingProperties,
        eventStoreBindings: List<EventStoreBinding>,
        snapshotStoreBindings: List<SnapshotStoreBinding>,
        eventStreamQueryServiceFactoryBindings: List<EventStreamQueryServiceFactoryBinding>,
        snapshotQueryServiceFactoryBindings: List<SnapshotQueryServiceFactoryBinding>,
    ): SnapshotStore {
        val resolvedRoutes = StorageRouteResolver(
            contextName = namedBoundedContext.contextName,
            snapshotEnabled = snapshotProperties.enabled,
            eventStoreBindings = eventStoreBindings,
            snapshotStoreBindings = snapshotStoreBindings,
            eventStreamQueryServiceFactoryBindings = eventStreamQueryServiceFactoryBindings,
            snapshotQueryServiceFactoryBindings = snapshotQueryServiceFactoryBindings,
            defaultEventStorage = eventStoreProperties.storage,
            defaultSnapshotStorage = snapshotProperties.storage,
        ).resolveSnapshotRoutes(storageRoutingProperties)
        return RoutingSnapshotStore(
            AggregateSnapshotStoreRegistry(
                defaultSnapshotStore = resolvedRoutes.defaultSnapshotStore,
                routes = resolvedRoutes.snapshotRoutes,
            ),
        )
    }

    @Bean
    @Primary
    @Conditional(OnEventStorageRouteCondition::class)
    fun routingEventStreamQueryServiceFactory(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        eventStoreProperties: EventStoreProperties,
        snapshotProperties: SnapshotProperties,
        storageRoutingProperties: StorageRoutingProperties,
        eventStoreBindings: List<EventStoreBinding>,
        snapshotStoreBindings: List<SnapshotStoreBinding>,
        eventStreamQueryServiceFactoryBindings: List<EventStreamQueryServiceFactoryBinding>,
        snapshotQueryServiceFactoryBindings: List<SnapshotQueryServiceFactoryBinding>,
    ): EventStreamQueryServiceFactory {
        val resolvedRoutes = StorageRouteResolver(
            contextName = namedBoundedContext.contextName,
            snapshotEnabled = snapshotProperties.enabled,
            eventStoreBindings = eventStoreBindings,
            snapshotStoreBindings = snapshotStoreBindings,
            eventStreamQueryServiceFactoryBindings = eventStreamQueryServiceFactoryBindings,
            snapshotQueryServiceFactoryBindings = snapshotQueryServiceFactoryBindings,
            defaultEventStorage = eventStoreProperties.storage,
            defaultSnapshotStorage = snapshotProperties.storage,
        ).resolveEventStreamQueryServiceFactoryRoutes(storageRoutingProperties)
        return RoutingEventStreamQueryServiceFactory(
            defaultEventStreamQueryServiceFactory = resolvedRoutes.defaultEventStreamQueryServiceFactory,
            routes = resolvedRoutes.eventStreamQueryServiceFactoryRoutes,
        )
    }

    @Bean
    @Primary
    @Conditional(OnSnapshotStorageRouteCondition::class)
    fun routingSnapshotQueryServiceFactory(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        eventStoreProperties: EventStoreProperties,
        snapshotProperties: SnapshotProperties,
        storageRoutingProperties: StorageRoutingProperties,
        eventStoreBindings: List<EventStoreBinding>,
        snapshotStoreBindings: List<SnapshotStoreBinding>,
        eventStreamQueryServiceFactoryBindings: List<EventStreamQueryServiceFactoryBinding>,
        snapshotQueryServiceFactoryBindings: List<SnapshotQueryServiceFactoryBinding>,
    ): SnapshotQueryServiceFactory {
        val resolvedRoutes = StorageRouteResolver(
            contextName = namedBoundedContext.contextName,
            snapshotEnabled = snapshotProperties.enabled,
            eventStoreBindings = eventStoreBindings,
            snapshotStoreBindings = snapshotStoreBindings,
            eventStreamQueryServiceFactoryBindings = eventStreamQueryServiceFactoryBindings,
            snapshotQueryServiceFactoryBindings = snapshotQueryServiceFactoryBindings,
            defaultEventStorage = eventStoreProperties.storage,
            defaultSnapshotStorage = snapshotProperties.storage,
        ).resolveSnapshotQueryServiceFactoryRoutes(storageRoutingProperties)
        return RoutingSnapshotQueryServiceFactory(
            defaultSnapshotQueryServiceFactory = resolvedRoutes.defaultSnapshotQueryServiceFactory,
            routes = resolvedRoutes.snapshotQueryServiceFactoryRoutes,
        )
    }
}

private class OnEventStorageRouteCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata
    ): ConditionOutcome {
        val matched = Binder.get(context.environment)
            .bindStorageRoutingProperties()
            .aggregates.values.any { aggregateRoute ->
                aggregateRoute.event != null
            }
        return storageRouteOutcome(matched, "event")
    }
}

private class OnSnapshotStorageRouteCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata
    ): ConditionOutcome {
        val matched = Binder.get(context.environment)
            .bindStorageRoutingProperties()
            .aggregates.values.any { aggregateRoute ->
                aggregateRoute.snapshot != null
            }
        return storageRouteOutcome(matched, "snapshot")
    }
}

private fun Binder.bindStorageRoutingProperties(): StorageRoutingProperties =
    bind(StorageRoutingProperties.PREFIX, StorageRoutingProperties::class.java)
        .let { result ->
            if (result.isBound) {
                result.get()
            } else {
                StorageRoutingProperties()
            }
        }

private fun storageRouteOutcome(matched: Boolean, channel: String): ConditionOutcome {
    val message = "Storage routing $channel routes are configured."
    return if (matched) {
        ConditionOutcome.match(message)
    } else {
        ConditionOutcome.noMatch(message)
    }
}
