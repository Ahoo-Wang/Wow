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

package me.ahoo.wow.cocache

import io.mockk.mockk
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.cocache.api.StateViewCache
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.ResolvableType

/** The extension on CoCache's starter, its memory backend, with the contract of `shop` on the class path. */
class WowCoCacheAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withClassLoader(ContractClassLoader(shopManifest(), javaClass.classLoader))
        .withConfiguration(
            AutoConfigurations.of(CoCacheAutoConfiguration::class.java, WowCoCacheAutoConfiguration::class.java),
        )
        .withBean(QueryApi::class.java, { mockk<QueryApi>() })

    private val cacheOfOrders = ResolvableType.forClassWithGenerics(StateViewCache::class.java, OrderView::class.java)

    @Test
    fun `a service should inject the cache of each view its contracts name, with nothing declared`() {
        runner.withPropertyValues("cocache.backend=memory", "cocache.caches.[wow.shop.order].ttl=5m").run { context ->
            context.startupFailure.assert().isNull()
            val cache = context.getBeanProvider<StateViewCache<OrderView>>(cacheOfOrders).getObject()
            cache.name.assert().isEqualTo("wow.shop.order")
            cache.assert().isSameAs(context.getBean(StateViewCaches::class.java).of<OrderView>())
            context.containsBean(StateViewCacheRegistrar.beanNameOf("shop.order")).assert().isTrue()
            // Created at startup, so CoCache's starter knows the cache its settings name.
            context.getBean(CacheRuntime::class.java).cacheNames.assert().contains("wow.shop.order")
        }
    }

    @Test
    fun `a service that does not run CoCache should get nothing`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(StateViewCaches::class.java).assert().isEmpty()
            context.containsBean(StateViewCacheRegistrar.beanNameOf("shop.order")).assert().isFalse()
        }
    }
}
