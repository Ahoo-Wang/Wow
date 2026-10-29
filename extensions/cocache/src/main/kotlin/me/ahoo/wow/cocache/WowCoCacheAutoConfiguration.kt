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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.sink.Sink
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.format.datetime.standard.DurationFormatterUtils
import java.time.Duration

/**
 * Shares the services' aggregate states through CoCache when the application runs CoCache (it has CoCache's
 * [CacheRuntime] bean, which CoCache's starter gives with `cocache.backend` set). This module lists it in
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; its ordering (after CoCache's
 * auto-configuration) is in `META-INF/spring-autoconfigure-metadata.properties`, so that it imports no Spring Boot
 * type.
 *
 * - **The readers**: a lazy `StateViewCache<V>` bean of each state view the peer contracts on the class path name
 *   ([StateViewCacheRegistrar]), from the service's [StateViewCaches], whose misses read through the node's
 *   [QueryApi]. Its caches are created at startup, through CoCache's starter when the application has it, so
 *   `cocache.caches.[wow.<context>.<aggregate>].*` applies to them.
 * - **The owner**: for each aggregate type `wow.cocache.publish` lists, a sink subscription that writes its states
 *   after each commit (`cocache-sink.<aggregate>`, [StateViewSink]), which the node runs beside the application's.
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
        val classLoader = classLoaderOf(beanFactory)
        val factory = StarterCaches.factoryOf(beanFactory, classLoader) ?: StateViewCaches.CacheFactory.of(runtime)
        return StateViewCaches(factory) { queries.getObject() }.apply {
            declare(ContractViews.onClassPath(classLoader).values)
        }
    }

    @Bean
    @Conditional(Publishes::class)
    fun wowCoCacheSinks(
        caches: StateViewCaches,
        manifest: Manifest,
        environment: Environment,
        beanFactory: ConfigurableListableBeanFactory,
    ): PublishedSinks = sinksOf(
        publishOf(environment),
        ttlOf(environment),
        manifest,
        ContractViews.onClassPath(classLoaderOf(beanFactory)),
        caches,
    )

    /**
     * The application runs CoCache: it has CoCache's runtime. Read once the beans are registered, CoCache's
     * auto-configuration before this one.
     */
    internal class RunsCoCache : ConfigurationCondition {
        override fun getConfigurationPhase(): ConfigurationCondition.ConfigurationPhase =
            ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean = runsCoCache(context)
    }

    /** The application runs CoCache, and lists the aggregate types whose states it writes. */
    internal class Publishes : ConfigurationCondition {
        override fun getConfigurationPhase(): ConfigurationCondition.ConfigurationPhase =
            ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            runsCoCache(context) && publishOf(context.environment).isNotEmpty()
    }

    companion object {
        /** The processor of the sink subscriptions that write the states: `cocache-sink.<aggregate>`. */
        const val PROCESSOR: String = "cocache-sink"

        /** Registers the `StateViewCache<V>` beans before the others are created. */
        @Bean
        @JvmStatic
        fun wowStateViewCacheRegistrar(): StateViewCacheRegistrar = StateViewCacheRegistrar()

        /**
         * The aggregate types whose states the service writes: `wow.cocache.publish`, read from the environment since
         * the module imports no Spring Boot, as a YAML list (`publish[0]`, ...) or a comma-separated value, else
         * [WowCoCacheProperties]' default, its one default.
         */
        internal fun publishOf(environment: Environment): List<String> {
            val listed = generateSequence(0) { it + 1 }
                .map { environment.getProperty("${WowCoCacheProperties.PUBLISH}[$it]") }
                .takeWhile { it != null }
                .filterNotNull()
                .toList()
            val joined = environment.getProperty(WowCoCacheProperties.PUBLISH)?.split(',').orEmpty()
            return (listed + joined).map(String::trim).filter(String::isNotEmpty).distinct()
                .ifEmpty { WowCoCacheProperties().publish }
        }

        /**
         * How long the written states live: `wow.cocache.ttl`, as Spring reads a duration (`10m`, `PT10M`), else
         * [WowCoCacheProperties]' default.
         */
        internal fun ttlOf(environment: Environment): Duration? {
            val value = environment.getProperty(WowCoCacheProperties.TTL)?.takeIf { it.isNotBlank() }
                ?: return WowCoCacheProperties().ttl
            val ttl = runCatching { DurationFormatterUtils.detectAndParse(value.trim()) }.getOrNull()
            configCheck(
                ttl != null && ttl > Duration.ZERO,
                WowCoCacheProperties.TTL,
                value,
                "Give a positive duration, as 10m."
            ) {
                "wow.cocache.ttl is $value, not a positive duration."
            }
            return ttl
        }

        /**
         * The sinks of the aggregate types [publish] lists, each writing for [ttl]: each must be one of [manifest]'s,
         * the service's own, and have a view among [views], its api's contract on the class path; the sinks write
         * through [caches].
         */
        internal fun sinksOf(
            publish: List<String>,
            ttl: Duration?,
            manifest: Manifest,
            views: Map<AggregateType, Class<*>>,
            caches: StateViewCaches,
        ): PublishedSinks {
            val own = manifest.contexts.flatMap { context ->
                context.aggregates.map { AggregateType(context.name, it.name) }
            }.toSet()
            val types = publish.map { name ->
                val type = typeOf(name)
                configCheck(
                    type in own,
                    WowCoCacheProperties.PUBLISH,
                    name,
                    "List an aggregate type this service serves."
                ) {
                    "wow.cocache.publish lists $name, which is no aggregate type of this service."
                }
                configCheck(
                    type in views,
                    WowCoCacheProperties.PUBLISH,
                    name,
                    "Depend on the context's api module, whose contract names the view; a state the contract " +
                        "withholds is not shared.",
                ) { "wow.cocache.publish lists $name, whose state no contract on the class path has a view of." }
                type
            }
            val sinks: Map<AggregateType, Sink> = types.associateWith { sinkOf(views.getValue(it), ttl, caches) }
            val streams = types.groupBy { it.context }.mapValues { (context, ofContext) ->
                ofContext.map { PublishedManifest(it.name, PublishedManifest.defaultTopic(context, it.name), true) }
            }
            return PublishedSinks(streams, PROCESSOR) { context, stream ->
                sinks.getValue(AggregateType(context, stream.aggregate))
            }
        }

        private fun typeOf(name: String): AggregateType {
            val context = name.substringBefore('.', "")
            val aggregate = name.substringAfter('.')
            configCheck(
                context.isNotEmpty() && aggregate.isNotEmpty() && '.' !in aggregate,
                WowCoCacheProperties.PUBLISH,
                name,
                "Name each as <context>.<aggregate>.",
            ) { "wow.cocache.publish lists $name, not an aggregate type's qualified name." }
            return AggregateType(context, aggregate)
        }

        private fun <V : Any> sinkOf(viewType: Class<V>, ttl: Duration?, caches: StateViewCaches): Sink =
            StateViewSink(viewType, caches.writerOf(viewType, ttl))

        private fun classLoaderOf(beans: ConfigurableListableBeanFactory): ClassLoader =
            beans.beanClassLoader ?: WowCoCacheAutoConfiguration::class.java.classLoader

        private fun runsCoCache(context: ConditionContext): Boolean =
            context.beanFactory?.getBeanNamesForType(CacheRuntime::class.java, true, false).orEmpty().isNotEmpty()
    }
}
