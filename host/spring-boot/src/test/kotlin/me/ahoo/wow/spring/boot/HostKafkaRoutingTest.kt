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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.command.CommandMessages
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.transport.EnvelopeCodec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.seconds

/**
 * An aggregate type routed through Kafka by `wow.aggregates.[<context>.<aggregate>].routing=kafka` (decision D143),
 * over in-memory topics: a command that waits only for ACCEPTED is answered 202 once it is on the context's command
 * topic, and the node's intake executes it from there.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostKafkaRoutingTest {
    private val host = startHost(
        "wow.aggregates.[bank.account].routing" to "kafka",
        sources = listOf(HostTestApp::class.java, InMemoryCommandTopics::class.java),
    )

    @AfterAll
    fun close() {
        host.close()
    }

    @Test
    fun `an accepted command should be answered 202 once it is on the command topic, then executed`(): Unit =
        runBlocking {
            host.node.options.kafkaRouted.assert().containsExactly(AggregateType(BANK, "account"))
            val accepted = host.post(
                "/bank/account/open-account",
                """{"owner":"kim"}""",
                mapOf(WowHeaders.WAIT to "accepted")
            )
            accepted.status.assert().isEqualTo(202)
            val commandId = accepted.body!!["commandId"].asString()
            val topics = host.context.getBean(InMemoryTopics::class.java)
            val message = topics.messages(COMMANDS).single()
            CommandMessages.commandOf(EnvelopeCodec.decode(message.value)).id.assert().isEqualTo(commandId)
            val id = accepted.body["ref"]["id"].asString()
            withTimeout(10.seconds) {
                while (id !in host.decided.ids) {
                    delay(5)
                }
            }
            // A plan that waits for more is answered once the owner signals it.
            val committed = host.post(
                "/bank/account/open-account",
                """{"owner":"lee"}""",
                mapOf(WowHeaders.WAIT to "committed")
            )
            committed.status.assert().isEqualTo(200)
            committed.body!!["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
            topics.messages(COMMANDS).size.assert().isEqualTo(2)
        }

    @Test
    fun `a routing for a type the node does not serve should be refused`() {
        val properties = WowProperties().apply {
            aggregates["bank.ledger"] = WowProperties.Aggregate().apply { routing = WowProperties.AggregateRouting.KAFKA }
        }
        runCatching { WowAutoConfiguration.kafkaRouted(properties, emptyList()) }.exceptionOrNull()!!.message.assert()
            .contains("wow.aggregates.[bank.ledger].routing names no aggregate type")
        WowAutoConfiguration.kafkaRouted(WowProperties(), emptyList()).assert().isEmpty()
    }

    /** In-memory topics as the node's, its command topic with the log's partitions. */
    @Configuration(proxyBeanMethods = false)
    class InMemoryCommandTopics {
        @Bean
        fun inMemoryTopics(): InMemoryTopics = InMemoryTopics().also {
            it.create(
                COMMANDS,
                WowProperties.DEFAULT_PARTITIONS
            )
        }

        @Bean
        fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics(topics, producer = topics)
    }

    private companion object {
        const val COMMANDS = "wow.bank.commands"
    }
}
