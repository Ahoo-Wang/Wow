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

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.wow.api.read.QueryApi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Reads the services' aggregate states through CoCache when the application runs CoCache (it has CoCache's
 * [CacheRuntime] bean, which CoCache's starter gives with `cocache.backend` set). This module lists it in
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; its ordering (after CoCache's
 * auto-configuration) is in `META-INF/spring-autoconfigure-metadata.properties`, so that it imports no Spring Boot
 * type.
 *
 * A service gets a lazy `StateViewCache<V>` bean of each state view the peer contracts on its class path name
 * ([StateViewCacheRegistrar]), from its [StateViewCaches], whose misses read through the node's [QueryApi]. The caches
 * are created at startup, through CoCache's starter when the application has it, so
 * `cocache.caches.[wow.<context>.<aggregate>].*` applies to them.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(WowCoCacheAutoConfiguration.RunsCoCache::class)
class WowCoCacheAutoConfiguration {
    @Bean
    fun wowStateViewCaches(
        runtime: CacheRuntime,
        beanFactory: ConfigurableListableBeanFactory,
        queries: ObjectProvider<QueryApi>,
    ): StateViewCaches {
        val classLoader = beanFactory.beanClassLoader ?: WowCoCacheAutoConfiguration::class.java.classLoader
        val factory = StarterCaches.factoryOf(beanFactory, classLoader) ?: StateViewCaches.CacheFactory.of(runtime)
        return StateViewCaches(factory) { queries.getObject() }.apply {
            declare(ContractViews.onClassPath(classLoader).values)
        }
    }

    /**
     * The application runs CoCache: it has CoCache's runtime. Read once the beans are registered, CoCache's
     * auto-configuration before this one.
     */
    internal class RunsCoCache : ConfigurationCondition {
        override fun getConfigurationPhase(): ConfigurationCondition.ConfigurationPhase =
            ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            context.beanFactory?.getBeanNamesForType(CacheRuntime::class.java, true, false).orEmpty().isNotEmpty()
    }

    companion object {
        /** Registers the `StateViewCache<V>` beans before the others are created. */
        @Bean
        @JvmStatic
        fun wowStateViewCacheRegistrar(): StateViewCacheRegistrar = StateViewCacheRegistrar()
    }
}
