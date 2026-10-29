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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.cocache.api.StateViewCache
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.ResolvableType
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

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
            context.getBeansOfType(PublishedSinks::class.java).assert().isEmpty()
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

    @Test
    fun `the owner should write the states it publishes through a sink subscription of its own`() {
        runner.withBean(Manifest::class.java, { shopManifest() })
            .withPropertyValues("cocache.backend=memory", "wow.cocache.publish[0]=shop.order")
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBean(
                    PublishedSinks::class.java
                ).toString().assert().isEqualTo("PublishedSinks(cocache-sink)")
            }
    }

    @Test
    fun `a listed aggregate type the service does not serve should fail the startup, naming the key`() {
        runner.withBean(Manifest::class.java, { Manifest() })
            .withPropertyValues("cocache.backend=memory", "wow.cocache.publish=shop.order")
            .run { context ->
                val failure = generateSequence(
                    context.startupFailure
                ) { it.cause }.filterIsInstance<WowConfigurationException>()
                failure.first().message.assert().contains("wow.cocache.publish")
            }
    }

    @Test
    fun `the sinks should refuse a type with no view, or a name that is no type`() {
        val runtime = runtimeOn(me.ahoo.cocache.core.memory.InMemoryBackend())
        val caches = StateViewCaches(StateViewCaches.CacheFactory.of(runtime)) { mockk() }
        val views = mapOf(ORDER to OrderView::class.java)
        fun sinksOf(publish: String, views: Map<AggregateType, Class<*>>) =
            runCatching { WowCoCacheAutoConfiguration.sinksOf(listOf(publish), null, shopManifest(), views, caches) }
        sinksOf("shop.order", views).getOrThrow().toString().assert().isEqualTo("PublishedSinks(cocache-sink)")
        (sinksOf("shop.order", emptyMap()).exceptionOrNull() as WowConfigurationException).message.assert()
            .contains("no contract on the class path")
        (sinksOf("order", views).exceptionOrNull() as WowConfigurationException).message.assert()
            .contains("qualified name")
        runtime.close()
    }

    @Test
    fun `the published types should be a YAML list or a comma-separated value`() {
        val listed = MockEnvironment().withProperty("wow.cocache.publish[0]", "shop.order")
            .withProperty("wow.cocache.publish[1]", " shop.cart ")
        WowCoCacheAutoConfiguration.publishOf(listed).assert().containsExactly("shop.order", "shop.cart")
        val joined = MockEnvironment().withProperty("wow.cocache.publish", "shop.order,shop.order, ,shop.cart")
        WowCoCacheAutoConfiguration.publishOf(joined).assert().containsExactly("shop.order", "shop.cart")
        WowCoCacheAutoConfiguration.publishOf(MockEnvironment()).assert().isEmpty()
    }

    @Test
    fun `the lifetime of the written states should be a duration as Spring writes one, else the cache's own`() {
        WowCoCacheAutoConfiguration.ttlOf(MockEnvironment()).assert().isNull()
        WowCoCacheAutoConfiguration.ttlOf(MockEnvironment().withProperty("wow.cocache.ttl", "10m"))
            .assert().isEqualTo(Duration.ofMinutes(10))
        WowCoCacheAutoConfiguration.ttlOf(MockEnvironment().withProperty("wow.cocache.ttl", "PT1H"))
            .assert().isEqualTo(Duration.ofHours(1))
        listOf("soon", "0s").forEach { value ->
            assertThrows<WowConfigurationException> {
                WowCoCacheAutoConfiguration.ttlOf(MockEnvironment().withProperty("wow.cocache.ttl", value))
            }.message.assert().contains("wow.cocache.ttl")
        }
    }
}
