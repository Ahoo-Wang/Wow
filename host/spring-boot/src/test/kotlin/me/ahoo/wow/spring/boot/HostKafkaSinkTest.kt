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

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kafka.KafkaPartitionedSource
import me.ahoo.wow.kafka.KafkaTopicAdmin
import me.ahoo.wow.kafka.KafkaTopicProducer
import me.ahoo.wow.kafka.OffsetGapPolicy
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.TopicDdl
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The Kafka Sink wired by [KafkaAutoConfiguration] (decisions D58, D132): with `wow.kafka.bootstrap-servers` set, every
 * stream the Manifest publishes reaches its topic through the [TopicProducer], beside the application's own
 * subscriptions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostKafkaSinkTest {
    private val host = startHost(
        "wow.kafka.bootstrap-servers" to "127.0.0.1:1",
        "wow.kafka.topic-prefix" to "test.",
        // No broker to validate the topics on.
        "wow.kafka.topics.ddl" to "none",
        sources = listOf(HostTestApp::class.java, KafkaBeans::class.java),
    )

    @AfterAll
    fun close() {
        host.close()
    }

    @Test
    fun `a published aggregate's commits should reach its topic`(): Unit = runBlocking {
        host.post("/bank/account/open-account", """{"owner":"kim"}""").status.assert().isEqualTo(200)
        val beans = host.context.getBean(KafkaBeans::class.java)
        withTimeout(10.seconds) {
            while (beans.sent.isEmpty() || beans.handled.isEmpty()) {
                delay(5)
            }
        }
        val (topic, message) = beans.sent.first()
        topic.assert().isEqualTo("test.wow.bank.account.state")
        val record = EnvelopeCodec.stateEventOf(message.value)
        record.ref.type.assert().isEqualTo(AggregateType(BANK, "account"))
        record.state.assert().isNotNull()
        message.key.assert().isEqualTo(record.ref.toString())
        // The application's own subscription runs beside the sink.
        beans.handled.first().assert().isEqualTo(record.ref.toString())
        host.context.getBeansOfType(KafkaTopicProducer::class.java).assert().isEmpty()
        host.node.context(BANK).views.readers.keys.assert().contains("kafka-sink.account")
    }

    @Test
    fun `without bootstrap servers nothing should be wired`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
            .run { context -> context.getBeansOfType(SubscriptionFactory::class.java).assert().isEmpty() }
    }

    @Test
    fun `with bootstrap servers the producer should be Kafka's, closed with the context`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
            .withPropertyValues(
                "wow.kafka.bootstrap-servers=127.0.0.1:1",
                "wow.kafka.properties.client.id=bank",
                "wow.kafka.topic-prefix=test.",
                "wow.kafka.poll-timeout=50ms",
                "wow.kafka.topics.ddl=auto",
                "wow.kafka.topics.replication-factor=3",
                "wow.kafka.topics.configs.retention.ms=600000",
                "wow.kafka.topics.partition-refresh=5s",
                "wow.kafka.offset-gap=earliest",
            )
            .withBean(Manifest::class.java, { Manifest(service = ServiceInfo("bank-service")) })
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                context.getBean(TopicProducer::class.java).assert().isInstanceOf(KafkaTopicProducer::class.java)
                val sinks = context.getBean(PublishedSinks::class.java)
                sinks.factory.assert().isNotNull()
                val topics = context.getBean(SourceTopics::class.java)
                val source = topics.source as KafkaPartitionedSource
                // An offset gap reads on from the earliest offset, as configured, and is counted (finding A2-11).
                source.offsetGap.assert().isEqualTo(OffsetGapPolicy.EARLIEST)
                context.getBean(MeterRegistry::class.java).get(KafkaAutoConfiguration.OFFSET_GAPS).functionCounter()
                    .count().assert().isEqualTo(0.0)
                topics.topicOf("wow.order.order.state").assert().isEqualTo("test.wow.order.order.state")
                // Commands across contexts go through the same producer; the intake is off unless asked for.
                topics.producer.assert().isSameAs(context.getBean(TopicProducer::class.java))
                topics.commandsOf("bank").assert().isEqualTo("test.wow.bank.commands")
                topics.receiptsOf("bank").assert().isEqualTo("test.wow.bank.receipts")
                topics.intake.assert().isFalse()
                // The topics the node owns are created through Kafka's admin, as configured (decision D168).
                topics.admin.assert().isSameAs(context.getBean(KafkaTopicAdmin::class.java))
                topics.ddl.assert().isEqualTo(TopicDdl.AUTO)
                topics.partitionRefresh.assert().isEqualTo(5.seconds)
                val kafka = context.getBean(KafkaProperties::class.java)
                kafka.topics.replicationFactor.assert().isEqualTo(3.toShort())
                kafka.topics.configs.assert().isEqualTo(mapOf("retention.ms" to "600000"))
                KafkaAutoConfiguration.options(kafka).metadataMaxAge.assert().isEqualTo(5.seconds)
                KafkaAutoConfiguration.options(context.getBean(KafkaProperties::class.java))
                    .pollTimeout.assert().isEqualTo(50.milliseconds)
                // The Manifest publishes nothing: no sink reads any state.
                sinks.factory.viewTypes(BANK, emptyList()).assert().isEmpty()
                context.getBean(KafkaProperties::class.java).properties.assert().isEqualTo(mapOf("client.id" to "bank"))
            }
        KafkaAutoConfiguration.options(KafkaProperties().apply { bootstrapServers = "b:9092" })
            .bootstrapServers.assert().isEqualTo("b:9092")
    }

    @Test
    fun `the command intake should be switched on by its property`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
            .withPropertyValues("wow.kafka.bootstrap-servers=127.0.0.1:1", "wow.kafka.command-intake=true")
            .withBean(Manifest::class.java, { Manifest(service = ServiceInfo("bank-service")) })
            .run { context ->
                context.getBean(SourceTopics::class.java).intake.assert().isTrue()
                // Validated by default (decision D168).
                context.getBean(SourceTopics::class.java).ddl.assert().isEqualTo(TopicDdl.VALIDATE)
            }
    }

    /** A recording producer, the Manifest publishing every aggregate, and a subscription of the application's own. */
    @Configuration(proxyBeanMethods = false)
    class KafkaBeans {
        val sent = CopyOnWriteArrayList<Pair<String, TopicMessage>>()
        val handled = CopyOnWriteArrayList<String>()

        @Bean
        fun recordingProducer(): TopicProducer = object : TopicProducer {
            override suspend fun send(topic: String, messages: List<TopicMessage>) {
                messages.forEach { sent += topic to it }
            }
        }

        @Bean
        fun publishingManifest(loader: ManifestLoader, models: WowModels, identity: NodeIdentity): Manifest =
            loader.load(ServiceInfo(identity.service), models.providers).let { manifest ->
                manifest.copyWith(
                    contexts = manifest.contexts.map { context ->
                        context.copyWith(
                            published = context.aggregates.map {
                                PublishedManifest(it.name, PublishedManifest.defaultTopic(context.name, it.name))
                            },
                        )
                    },
                )
            }

        @Bean
        fun applicationSubscriptions(): SubscriptionFactory = object : SubscriptionFactory {
            override fun subscriptions(context: ContextNode): List<Subscription> = listOf(
                Subscription("Audit.onEvent", SubscriptionFilter(context.context)) { handled += it.ref.toString() },
            )

            override fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> = emptySet()
        }
    }
}
