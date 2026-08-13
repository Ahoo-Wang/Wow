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
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean

@AutoConfiguration(
    after = [StorageRoutingAutoConfiguration::class],
    beforeName = ["me.ahoo.wow.spring.boot.starter.query.QueryGatewayAutoConfiguration"],
)
@ConditionalOnBean(EventStoreBinding::class)
internal class CanonicalStorageRouteConfiguration {

    @Bean("resolvedStorageRouteSnapshot")
    fun resolvedStorageRouteSnapshot(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        eventStoreProperties: EventStoreProperties,
        snapshotProperties: SnapshotProperties,
        storageRoutingProperties: StorageRoutingProperties,
        eventStoreBindings: List<EventStoreBinding>,
        snapshotStoreBindings: List<SnapshotStoreBinding>,
        eventStreamQueryServiceFactoryBindings: List<EventStreamQueryServiceFactoryBinding>,
        snapshotQueryServiceFactoryBindings: List<SnapshotQueryServiceFactoryBinding>,
        queryBackendBindings: List<QueryBackendBinding>,
    ): ResolvedStorageRouteSnapshot = StorageRouteCoordinator(
        contextName = namedBoundedContext.contextName,
        snapshotEnabled = snapshotProperties.enabled,
        eventStoreBindings = eventStoreBindings,
        snapshotStoreBindings = snapshotStoreBindings,
        eventStreamQueryServiceFactoryBindings = eventStreamQueryServiceFactoryBindings,
        snapshotQueryServiceFactoryBindings = snapshotQueryServiceFactoryBindings,
        queryBackendBindings = queryBackendBindings,
        defaultEventStorage = eventStoreProperties.storage,
        defaultSnapshotStorage = snapshotProperties.storage,
    ).resolve(storageRoutingProperties)

    @Bean("queryBackendRouteSnapshot")
    fun queryBackendRouteSnapshot(
        routeSnapshot: ResolvedStorageRouteSnapshot,
    ): QueryBackendRouteSnapshot = routeSnapshot.queryBackendRoutes()
}
