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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.env.MockEnvironment

/**
 * `wow.transport` (design K9, CQ3): `auto` takes a configured provider's transport, else the topics in the JVM in
 * development, else none; a node without one whose Manifest needs topics refuses to start; `memory` is for
 * development alone.
 */
class HostTransportTest {
    private val providers = PortProviders.load(javaClass.classLoader)

    private fun environment(vararg properties: Pair<String, String>, profiles: Array<String> = emptyArray()) =
        MockEnvironment().apply {
            setProperty("wow.internal.host", "0.0.0.0")
            properties.forEach { (key, value) -> setProperty(key, value) }
            setActiveProfiles(*profiles)
        }

    @Test
    fun `auto should take the configured transport, else memory in development, else none`() {
        TransportSelection.of(environment("wow.kafka.bootstrap-servers" to "k:9092"), providers, beans = false)
            .assert().isEqualTo(TransportSelection.PROVIDER)
        TransportSelection.of(environment(), providers, beans = true).assert().isEqualTo(TransportSelection.PROVIDER)
        TransportSelection.of(environment(profiles = arrayOf("dev")), providers, beans = false)
            .assert().isEqualTo(TransportSelection.MEMORY)
        TransportSelection.of(environment("wow.internal.host" to "127.0.0.1"), providers, beans = false)
            .assert().isEqualTo(TransportSelection.MEMORY)
        TransportSelection.of(environment(), providers, beans = false).assert().isEqualTo(TransportSelection.NONE)
    }

    @Test
    fun `a named transport should be selected whatever is configured`() {
        TransportSelection.of(environment("wow.transport" to "memory"), providers, beans = false)
            .assert().isEqualTo(TransportSelection.MEMORY)
        TransportSelection.of(environment("wow.transport" to "Kafka"), providers, beans = false)
            .assert().isEqualTo(TransportSelection.PROVIDER)
    }

    @Test
    fun `what needs a transport should be named, and nothing needed should pass`() {
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(BANK, published = listOf(PublishedManifest("account", "wow.bank.account.state"))),
                ContextManifest("other", published = listOf(PublishedManifest("x", "wow.other.x.state"))),
            ),
        )
        val needs = TransportNeeds.of(setOf(AggregateType(BANK, "account")), ledger = true, intake = true)
        needs.assert().hasSize(3)
        needs.first().assert().contains("routes $BANK.account through Kafka")
        // Decision D322: a published stream is no need; the node without a transport names those it does not publish.
        TransportNeeds.of(emptySet(), ledger = false, intake = false).assert().isEmpty()
        TransportNeeds.unpublished(manifest, setOf(BANK)).assert().containsExactly("$BANK.account")
        TransportNeeds.check(emptyList(), "auto")
        assertThrows<WowConfigurationException> { TransportNeeds.check(needs, "auto") }.also {
            it.key.assert().isEqualTo("wow.transport")
            it.message.assert().contains("needs a topic transport", "wow.ops.publish.enabled")
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `a node outside development whose aggregates are published should start without a transport, saying so`(
        output: CapturedOutput,
    ) {
        // Decision D322: every aggregate is published by default (D156), which needs no transport.
        startHost(
            *DEPLOYED,
            sources = listOf(
                HostTestApp::class.java,
                AnonymousCallers::class.java,
                HostClickHouseSinkTest.PublishingManifest::class.java,
            ),
        ).use { host ->
            host.context.getBeanProvider(SourceTopics::class.java).ifAvailable.assert().isNull()
        }
        output.out.assert().contains(
            "runs without a topic transport, so it does not publish its streams [$BANK.account",
            "configure Kafka (wow.kafka.bootstrap-servers)",
        )
    }

    @Test
    fun `a node outside development that routes commands through Kafka should refuse to start without a transport`() {
        val failure = assertThrows<Exception> {
            startHost(
                *DEPLOYED,
                "wow.aggregates.[$BANK.account].routing" to "kafka",
                sources = listOf(HostTestApp::class.java, AnonymousCallers::class.java),
            ).close()
        }
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first().also {
            it.key.assert().isEqualTo("wow.transport")
            it.message.assert().contains("routes $BANK.account through Kafka")
        }
    }

    @Test
    fun `a node outside development that needs no topics should start without a transport`() {
        startHost(*DEPLOYED, sources = listOf(HostTestApp::class.java, AnonymousCallers::class.java)).use { host ->
            host.context.getBeanProvider(SourceTopics::class.java).ifAvailable.assert().isNull()
        }
    }

    @Test
    fun `a node in development should keep its topics in its JVM`() {
        startHost().use { host ->
            host.context.getBean(SourceTopics::class.java).intake.assert().isTrue()
        }
    }

    @Test
    fun `memory outside development should be refused`() {
        val failure = assertThrows<Exception> {
            startHost(
                *DEPLOYED,
                "wow.transport" to "memory",
                sources = listOf(HostTestApp::class.java, AnonymousCallers::class.java),
            ).close()
        }
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
            .key.assert().isEqualTo("wow.transport")
    }

    @Test
    fun `development should be derived from the profiles or a loopback internal port`() {
        val loopback = WowProperties().apply { internal.host = "127.0.0.1" }
        DevelopmentMode.of(loopback, environment()).reason.assert().contains("127.0.0.1")
        val everywhere = WowProperties().apply { internal.host = "0.0.0.0" }
        DevelopmentMode.of(everywhere, environment()).active.assert().isFalse()
        DevelopmentMode.of(everywhere, environment(profiles = arrayOf("local"))).reason
            .assert().isEqualTo("profile local")
        NodeIdentity.bindHostOf(WowProperties().apply { node.address = "[::1]:9090" }).assert().isEqualTo("::1")
        NodeIdentity.bindHostOf(WowProperties().apply { node.address = "10.0.0.1:9090" })
            .assert().isEqualTo("10.0.0.1")
        NodeIdentity.bindHostOf(WowProperties()).assert().isNotBlank()
    }

    @Test
    fun `a named transport that is not configured should fail the start, naming it`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TransportAutoConfiguration::class.java))
            .withPropertyValues("wow.transport=kafka")
            .withBean(Manifest::class.java, { Manifest(service = ServiceInfo("bank-service")) })
            .withBean(WowProperties::class.java, { WowProperties().apply { transport = "kafka" } })
            .run { context ->
                generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString()
                    .assert().contains("wow.transport is kafka, but no transport of that type is configured")
            }
    }

    private companion object {
        /** A node bound to every interface, in memory on purpose, so that the backend is not what it refuses. */
        val DEPLOYED = arrayOf<Pair<String, Any>>("wow.internal.host" to "0.0.0.0", "wow.backend" to "memory")
    }
}
