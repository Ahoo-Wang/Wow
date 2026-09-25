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
import me.ahoo.wow.query.QueryBackendProvider
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.RoutingEventStreamQueryBackendFactory
import me.ahoo.wow.query.snapshot.RoutingSnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
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
import me.ahoo.wow.spring.boot.starter.redis.RedisEventSourcingAutoConfiguration
import org.springframework.beans.factory.InitializingBean
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
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

@AutoConfiguration(
    after = [
        EventSourcingAutoConfiguration::class,
        EventStoreAutoConfiguration::class,
        SnapshotAutoConfiguration::class,
        MongoEventSourcingAutoConfiguration::class,
        RedisEventSourcingAutoConfiguration::class,
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

    /**
     * Rejects a default channel that names both a built-in `storage` and a `binding`, as a route does: the two
     * exclude each other, and silently preferring one would hide a misconfiguration.
     */
    @Bean
    fun storageDefaultsValidation(environment: Environment): InitializingBean = InitializingBean {
        listOf(
            EventStoreProperties.STORAGE to EventStoreProperties.BINDING,
            SnapshotProperties.STORAGE to SnapshotProperties.BINDING,
        ).forEach { (storage, binding) ->
            check(!(environment.containsProperty(storage) && !environment.getProperty(binding).isNullOrBlank())) {
                "[$storage] and [$binding] exclude each other: configure either the built-in storage or a binding."
            }
        }
    }

    @Bean(destroyMethod = "")
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
        queryBackendProviders: List<QueryBackendProvider>,
    ): EventStore {
        val resolvedRoutes = StorageRouteResolver(
            contextName = namedBoundedContext.contextName,
            snapshotEnabled = snapshotProperties.enabled,
            eventStoreBindings = eventStoreBindings,
            snapshotStoreBindings = snapshotStoreBindings,
            queryBackendProviders = queryBackendProviders,
            defaultEventStorage = eventStoreProperties.storage,
            defaultSnapshotStorage = snapshotProperties.storage,
            defaultEventBinding = eventStoreProperties.binding,
            defaultSnapshotBinding = snapshotProperties.binding,
        ).resolveEventRoutes(storageRoutingProperties)
        return RoutingEventStore(
            AggregateEventStoreRegistry(
                defaultEventStore = resolvedRoutes.defaultEventStore,
                routes = resolvedRoutes.eventRoutes,
            ),
        )
    }

    @Bean(destroyMethod = "")
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
        queryBackendProviders: List<QueryBackendProvider>,
    ): SnapshotStore {
        val resolvedRoutes = StorageRouteResolver(
            contextName = namedBoundedContext.contextName,
            snapshotEnabled = snapshotProperties.enabled,
            eventStoreBindings = eventStoreBindings,
            snapshotStoreBindings = snapshotStoreBindings,
            queryBackendProviders = queryBackendProviders,
            defaultEventStorage = eventStoreProperties.storage,
            defaultSnapshotStorage = snapshotProperties.storage,
            defaultEventBinding = eventStoreProperties.binding,
            defaultSnapshotBinding = snapshotProperties.binding,
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
    fun routingEventStreamQueryBackendFactory(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        eventStoreProperties: EventStoreProperties,
        snapshotProperties: SnapshotProperties,
        storageRoutingProperties: StorageRoutingProperties,
        eventStoreBindings: List<EventStoreBinding>,
        snapshotStoreBindings: List<SnapshotStoreBinding>,
        queryBackendProviders: List<QueryBackendProvider>,
    ): EventStreamQueryBackendFactory {
        val resolvedRoutes = StorageRouteResolver(
            contextName = namedBoundedContext.contextName,
            snapshotEnabled = snapshotProperties.enabled,
            eventStoreBindings = eventStoreBindings,
            snapshotStoreBindings = snapshotStoreBindings,
            queryBackendProviders = queryBackendProviders,
            defaultEventStorage = eventStoreProperties.storage,
            defaultSnapshotStorage = snapshotProperties.storage,
            defaultEventBinding = eventStoreProperties.binding,
            defaultSnapshotBinding = snapshotProperties.binding,
        ).resolveEventStreamQueryBackendFactoryRoutes(storageRoutingProperties)
        return RoutingEventStreamQueryBackendFactory(
            defaultFactory = resolvedRoutes.defaultEventStreamQueryBackendFactory,
            routes = resolvedRoutes.eventStreamQueryBackendFactoryRoutes,
        )
    }

    @Bean
    @Primary
    @Conditional(OnSnapshotStorageRouteCondition::class)
    fun routingSnapshotQueryBackendFactory(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        eventStoreProperties: EventStoreProperties,
        snapshotProperties: SnapshotProperties,
        storageRoutingProperties: StorageRoutingProperties,
        eventStoreBindings: List<EventStoreBinding>,
        snapshotStoreBindings: List<SnapshotStoreBinding>,
        queryBackendProviders: List<QueryBackendProvider>,
    ): SnapshotQueryBackendFactory {
        val resolvedRoutes = StorageRouteResolver(
            contextName = namedBoundedContext.contextName,
            snapshotEnabled = snapshotProperties.enabled,
            eventStoreBindings = eventStoreBindings,
            snapshotStoreBindings = snapshotStoreBindings,
            queryBackendProviders = queryBackendProviders,
            defaultEventStorage = eventStoreProperties.storage,
            defaultSnapshotStorage = snapshotProperties.storage,
            defaultEventBinding = eventStoreProperties.binding,
            defaultSnapshotBinding = snapshotProperties.binding,
        ).resolveSnapshotQueryBackendFactoryRoutes(storageRoutingProperties)
        return RoutingSnapshotQueryBackendFactory(
            defaultFactory = resolvedRoutes.defaultSnapshotQueryBackendFactory,
            routes = resolvedRoutes.snapshotQueryBackendFactoryRoutes,
        )
    }
}

private class OnEventStorageRouteCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata
    ): ConditionOutcome {
        val binder = Binder.get(context.environment)
        val matched = binder.hasDefaultBinding(EventStoreProperties.BINDING) ||
            binder.bindStorageRoutingProperties().aggregates.values.any { aggregateRoute ->
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
        val binder = Binder.get(context.environment)
        val matched = binder.hasDefaultBinding(SnapshotProperties.BINDING) ||
            binder.bindStorageRoutingProperties().aggregates.values.any { aggregateRoute ->
                aggregateRoute.snapshot != null
            }
        return storageRouteOutcome(matched, "snapshot")
    }
}

/** Whether the default channel names a binding: then the routing store serves the default even without routes. */
private fun Binder.hasDefaultBinding(property: String): Boolean =
    bind(property, String::class.java).map(String::isNotBlank).orElse(false) == true

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
