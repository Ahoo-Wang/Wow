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

package me.ahoo.wow.spring.boot.transport

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.read.StateMasks
import me.ahoo.wow.spring.boot.BANK
import me.ahoo.wow.spring.boot.query.QueryProperties
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * DX gap G27: `wow.transport` gives a node in development its topics in its JVM, for trying a service out without
 * Kafka; the runner's node is in development (the `dev` profile), the deployment's is not, whatever it binds.
 */
class HostInMemoryTopicsTest {
    private val deployment = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InMemoryTopicsAutoConfiguration::class.java))
        .withBean(Manifest::class.java, { Manifest(service = ServiceInfo("bank-service")) })
        .withBean(StateMasks::class.java, { PayloadMasks.NONE })

    private val runner = deployment.withPropertyValues("spring.profiles.active=dev")

    @Test
    fun `outside development nothing should be wired, a loopback internal port included`() {
        // A loopback port states no development (#6972).
        deployment.withPropertyValues("wow.internal.host=127.0.0.1").run { context ->
            context.getBeansOfType(InMemoryTopics::class.java).assert().isEmpty()
            context.getBeansOfType(SourceTopics::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `development should give the node in-memory topics of its log's partitions, with the intake and the sinks`() {
        runner.withPropertyValues("wow.log.partitions=4").run { context ->
            val topics = context.getBean(InMemoryTopics::class.java)
            val source = context.getBean(SourceTopics::class.java)
            source.producer.assert().isSameAs(topics)
            source.intake.assert().isTrue()
            context.getBean(InMemoryTopicsAutoConfiguration.IN_MEMORY_SINKS, PublishedSinks::class.java).assert()
                .isNotNull()
            runBlocking { topics.partitions("wow.bank.commands") }.assert().hasSize(4)
        }
    }

    @Test
    fun `the sinks should publish through the node's state masks, and a node without them should not start`() {
        runner.run { context ->
            context.getBean(InMemoryTopicsAutoConfiguration.IN_MEMORY_SINKS, PublishedSinks::class.java).assert()
                .isNotNull()
        }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InMemoryTopicsAutoConfiguration::class.java))
            .withBean(Manifest::class.java, { Manifest(service = ServiceInfo("bank-service")) })
            .withPropertyValues("spring.profiles.active=dev")
            .run { context ->
                generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }.assert()
                    .contains(StateMasks::class.java.name)
            }
    }

    @Test
    fun `the sinks should publish by the service's display-shared switch, on by default, whether queries run or not`() {
        fun environment(vararg properties: Pair<String, Any>) =
            StandardEnvironment().apply { propertySources.addFirst(MapPropertySource("test", properties.toMap())) }
        QueryProperties.displaySharedOf(environment()).assert().isTrue()
        QueryProperties.displaySharedOf(environment(QueryProperties.DISPLAY_SHARED to "false")).assert().isFalse()
        QueryProperties.displaySharedOf(environment("wow.query.enabled" to "false")).assert().isTrue()
    }

    @Test
    fun `memory should be the node's topics even with Kafka configured, and auto should take Kafka`() {
        val both = runner.withConfiguration(AutoConfigurations.of(TransportAutoConfiguration::class.java))
        both.withPropertyValues("wow.transport=memory", "wow.kafka.bootstrap-servers=127.0.0.1:9092").run { context ->
            context.getBean(InMemoryTopics::class.java).assert().isNotNull()
            context.containsBean(TransportAutoConfiguration.TRANSPORT_SINKS).assert().isFalse()
        }
        both.withPropertyValues("wow.kafka.bootstrap-servers=127.0.0.1:9092").run { context ->
            context.getBeansOfType(InMemoryTopics::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `a blank Kafka address should not count as Kafka`() {
        runner.withConfiguration(AutoConfigurations.of(TransportAutoConfiguration::class.java))
            .withPropertyValues("wow.kafka.bootstrap-servers= ").run { context ->
                context.startupFailure.assert().isNull()
                context.getBean(InMemoryTopics::class.java).assert().isNotNull()
                context.containsBean(TransportAutoConfiguration.TRANSPORT_SINKS).assert().isFalse()
                context.getBeansOfType(PublishedSinks::class.java).keys.assert()
                    .containsExactly(InMemoryTopicsAutoConfiguration.IN_MEMORY_SINKS)
            }
    }

    @Test
    fun `a host in development should execute its command topics and warn`() {
        startHost().use { host ->
            host.node.context(BANK).intake.assert().isNotNull()
        }
        InMemoryTopicsAutoConfiguration.WARNING.assert().contains("for local development and tests only")
    }
}
