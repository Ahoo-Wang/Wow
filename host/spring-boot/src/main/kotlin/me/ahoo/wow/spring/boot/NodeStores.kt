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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.spi.store.StoreProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

/**
 * The node's stores: the providers of their types ([StoreProviders]), the store instances the host opens and owns
 * ([HostBackends], [StoreInstances]), and the lifecycle that creates their schemas before anything uses them. Imported
 * by [WowAutoConfiguration], whose order and conditions it shares.
 */
@Configuration(proxyBeanMethods = false)
internal class NodeStores {
    /**
     * The store providers: memory, built in (its snapshots queryable unless `wow.query.memory.enabled` is false),
     * those on the classpath (`ServiceLoader`: PostgreSQL's, MongoDB's, a community backend's) and the application's
     * [StoreProvider] beans, each over the one of its type before it.
     */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowStoreProviders(
        beanFactory: ConfigurableListableBeanFactory,
        queries: ObjectProvider<QueryProperties>,
        providers: ObjectProvider<StoreProvider>,
    ): StoreProviders {
        // A store in memory is queried unless the application turns it off: there is nothing else to query it with.
        // Without the query plane (wow.query.enabled=false, or no wow-query) there are no query properties to say.
        val memoryQueries = queries.ifAvailable?.memory?.enabled ?: true
        return StoreProviders.load(
            beanFactory.beanClassLoader,
            memory = MemoryStoreProvider(memoryQueries),
            beans = providers.orderedStream().toList(),
        )
    }

    @Bean(BACKENDS)
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowBackends(
        properties: WowProperties,
        beanFactory: ConfigurableListableBeanFactory,
        factories: ObjectProvider<BackendFactory>,
        providers: StoreProviders,
        models: WowModels,
        identity: ObjectProvider<NodeIdentity>,
        development: DevelopmentMode,
        environment: Environment,
        clock: Clock,
    ): HostBackends {
        factories.ifAvailable?.let { return HostBackends.of(it) }
        val node = identity.ifAvailable
        val placements = properties.storePlacements()
        val types = models.models.map { it.type }
        val stores = StoreInstances.open(
            properties,
            providers,
            BeanStoreResources(beanFactory, BACKENDS),
            node?.service.orEmpty(),
            development.reason,
            clock,
        ) { prefix -> PortProviders.settingsOf(environment, prefix) }
        // The next statement hands the stores to their owner: nothing runs between.
        return StoreInstances.backends(stores, placements, types, properties.log.partitions)
    }

    @Bean
    @InternalWowApi
    fun wowBackendsLifecycle(backends: HostBackends): BackendsLifecycle = BackendsLifecycle(backends)

    companion object {
        /** The name of the [HostBackends] bean, which owns the stores the host opens. */
        const val BACKENDS: String = "wowBackends"
    }
}
