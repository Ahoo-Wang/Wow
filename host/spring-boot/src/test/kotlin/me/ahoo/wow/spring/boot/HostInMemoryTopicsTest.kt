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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.sink.PublishedSinks
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * DX gap G27: `wow.transport` gives a node in development its topics in its JVM, for trying a service out without
 * Kafka; the runner's node is in development (a loopback internal port) unless a test binds it elsewhere.
 */
class HostInMemoryTopicsTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InMemoryTopicsAutoConfiguration::class.java))
        .withBean(Manifest::class.java, { Manifest(service = ServiceInfo("bank-service")) })
        .withPropertyValues("wow.internal.host=127.0.0.1")

    @Test
    fun `outside development nothing should be wired`() {
        runner.withPropertyValues("wow.internal.host=0.0.0.0").run { context ->
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
