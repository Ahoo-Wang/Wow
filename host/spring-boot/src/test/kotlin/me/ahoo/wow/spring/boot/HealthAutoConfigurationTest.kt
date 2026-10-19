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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.runtime.health.NodeHealth
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.CompositeHealthContributor
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.DefaultResourceLoader

/**
 * [HealthAutoConfiguration] and [HealthGroupsEnvironmentPostProcessor] on their own (review R3.5-3): every condition,
 * and the contributors' wiring to the node's health.
 */
class HealthAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HealthAutoConfiguration::class.java))
        .withUserConfiguration(NodeConfiguration::class.java)

    @Test
    fun `the contributors should report the node's health, its readiness and its liveness`() {
        runner.withPropertyValues("wow.health.sample-interval=1s", "wow.health.stores.down-after=2s").run { context ->
            val health = context.getBean(NodeHealth::class.java)
            health.isRunning.assert().isTrue()
            health.options.storeDownAfter.inWholeSeconds.assert().isEqualTo(2L)
            val wow = context.getBean(HealthAutoConfiguration.WOW_CONTRIBUTOR, CompositeHealthContributor::class.java)
            wow.stream().map { it.name }.toList().assert().containsExactly(
                NodeHealth.NODE,
                NodeHealth.OWNERSHIP,
                NodeHealth.STORES,
                NodeHealth.TOPICS,
                NodeHealth.SUBSCRIPTIONS,
                NodeHealth.EXECUTOR,
                NodeHealth.LIVENESS,
            )
            // The node has not started: not ready, and live.
            (wow.getContributor(NodeHealth.NODE) as HealthIndicator).health()!!.status.assert().isEqualTo(Status.DOWN)
            (wow.getContributor(NodeHealth.EXECUTOR) as HealthIndicator).health()!!.status.assert()
                .isEqualTo(Status.UP)
            val readiness = context.getBean(HealthAutoConfiguration.READINESS_INDICATOR, HealthIndicator::class.java)
            readiness.health()!!.status.assert().isEqualTo(Status.DOWN)
            val liveness = context.getBean(HealthAutoConfiguration.LIVENESS_INDICATOR, HealthIndicator::class.java)
            liveness.health()!!.status.assert().isEqualTo(Status.UP)
        }
    }

    @Test
    fun `an application's own node health should be the one the contributors read`() {
        runner.withBean("ownHealth", NodeHealth::class.java, { NodeHealth(node()) }).run { context ->
            context.getBeansOfType(NodeHealth::class.java).keys.assert().containsExactly("ownHealth")
            context.containsBean(HealthAutoConfiguration.READINESS_INDICATOR).assert().isTrue()
        }
    }

    @Test
    fun `no contributor should be made when the node's health is off or Actuator's health is absent`() {
        runner.withPropertyValues("wow.health.enabled=false").run { context ->
            context.getBeansOfType(NodeHealth::class.java).assert().isEmpty()
            context.containsBean(HealthAutoConfiguration.WOW_CONTRIBUTOR).assert().isFalse()
        }
        runner.withClassLoader(FilteredClassLoader(HealthIndicator::class.java)).run { context ->
            context.getBeansOfType(NodeHealth::class.java).assert().isEmpty()
            context.containsBean(HealthAutoConfiguration.LIVENESS_INDICATOR).assert().isFalse()
        }
    }

    @Test
    fun `the probe groups should get the node's indicators unless the application names their members`() {
        val processor = HealthGroupsEnvironmentPostProcessor()
        val defaults = environment()
        processor.postProcessEnvironment(defaults, SpringApplication())
        HealthGroupsEnvironmentPostProcessor.DEFAULTS.forEach { (key, value) ->
            defaults.getProperty(key).assert().isEqualTo(value)
        }
        // Lowest precedence: after every other source.
        defaults.propertySources.last().name.assert().isEqualTo(HealthGroupsEnvironmentPostProcessor.SOURCE)

        val own = environment("management.endpoint.health.group.readiness.include" to "readinessState,db")
        processor.postProcessEnvironment(own, SpringApplication())
        own.getProperty("management.endpoint.health.group.readiness.include").assert().isEqualTo("readinessState,db")
        own.getProperty("management.endpoint.health.group.liveness.include").assert()
            .isEqualTo("livenessState,wowLiveness")

        val named = environment(
            *HealthGroupsEnvironmentPostProcessor.DEFAULTS.map { it.key to it.value }.toTypedArray()
        )
        processor.postProcessEnvironment(named, SpringApplication())
        named.propertySources.contains(HealthGroupsEnvironmentPostProcessor.SOURCE).assert().isFalse()
    }

    @Test
    fun `the probe groups should be left alone when the node's health or the probes are off, or Actuator is absent`() {
        val processor = HealthGroupsEnvironmentPostProcessor()
        listOf(
            environment(HealthGroupsEnvironmentPostProcessor.ENABLED to "false"),
            environment(HealthGroupsEnvironmentPostProcessor.PROBES to "false"),
        ).forEach { off ->
            processor.postProcessEnvironment(off, SpringApplication())
            off.propertySources.contains(HealthGroupsEnvironmentPostProcessor.SOURCE).assert().isFalse()
        }
        listOf(HealthIndicator::class.java, Endpoint::class.java).forEach { missing ->
            val application = SpringApplication()
            application.setResourceLoader(DefaultResourceLoader(FilteredClassLoader(missing)))
            val absent = environment()
            processor.postProcessEnvironment(absent, application)
            absent.propertySources.contains(HealthGroupsEnvironmentPostProcessor.SOURCE).assert().isFalse()
        }
    }

    private fun environment(vararg properties: Pair<String, Any>): StandardEnvironment = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("app", properties.toMap()))
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WowProperties::class)
    class NodeConfiguration {
        @Bean(destroyMethod = "")
        fun serviceNode(): ServiceNode = node()
    }

    private companion object {
        /** A node of no context, never started. */
        fun node(): ServiceNode = ServiceNode(
            service = "health-service",
            node = "node-1",
            address = "127.0.0.1:9090",
            models = emptyList(),
            manifest = Manifest(service = ServiceInfo("health-service")),
            backends = { error("No context.") },
            channel = { _, _ -> ForwardReply.refused(Refusal.UNKNOWN_TYPE) },
        )
    }
}
