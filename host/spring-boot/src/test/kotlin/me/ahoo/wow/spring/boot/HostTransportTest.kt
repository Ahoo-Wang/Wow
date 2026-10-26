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
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        val needs = TransportNeeds.of(manifest, setOf(BANK), setOf(AggregateType(BANK, "account")), true, true)
        needs.assert().hasSize(4)
        needs.first().assert().contains("$BANK.account").doesNotContain("other.x")
        TransportNeeds.of(manifest, setOf("none"), emptySet(), ledger = false, intake = false).assert().isEmpty()
        TransportNeeds.check(emptyList(), "auto")
        assertThrows<WowConfigurationException> { TransportNeeds.check(needs, "auto") }.also {
            it.key.assert().isEqualTo("wow.transport")
            it.message.assert().contains("needs a topic transport", "wow.ops.publish.enabled")
        }
    }

    @Test
    fun `a node outside development whose Manifest publishes a stream should refuse to start without a transport`() {
        val failure = assertThrows<Exception> {
            startHost(
                *DEPLOYED,
                sources = listOf(
                    HostTestApp::class.java,
                    AnonymousCallers::class.java,
                    HostClickHouseSinkTest.PublishingManifest::class.java,
                ),
            ).close()
        }
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first().also {
            it.key.assert().isEqualTo("wow.transport")
            it.message.assert().contains("publishes the streams")
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

    private companion object {
        /** A node bound to every interface, in memory on purpose, so that the backend is not what it refuses. */
        val DEPLOYED = arrayOf<Pair<String, Any>>("wow.internal.host" to "0.0.0.0", "wow.backend" to "memory")
    }
}
