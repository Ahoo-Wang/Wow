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

package me.ahoo.wow.spring.boot.starter.kafka

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.kafka.AcknowledgeKafkaRecordDecodeFailureHandler
import me.ahoo.wow.kafka.KafkaReceiverPolicy
import me.ahoo.wow.kafka.KafkaRecordDecodeFailureHandler
import me.ahoo.wow.kafka.ReceiverOptionsCustomizer
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.opentelemetry.WowOpenTelemetryAutoConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.util.retry.RetryBackoffSpec
import java.time.Duration

internal class KafkaAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with kafka command bus and event bus`() {
        contextRunner
            .enableWow()
            .withPropertyValues("${KafkaProperties.PREFIX}.bootstrap-servers=kafka")
            .withUserConfiguration(
                WowOpenTelemetryAutoConfiguration::class.java,
                KafkaAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(ReceiverOptionsCustomizer::class.java)
                    .hasSingleBean(KafkaReceiverPolicy::class.java)
                    .hasSingleBean(KafkaRecordDecodeFailureHandler::class.java)
                    .hasSingleBean(CommandBus::class.java)
                    .hasSingleBean(DomainEventBus::class.java)
            }
    }

    @Test
    fun `should bind receiver safety policy`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${KafkaProperties.PREFIX}.bootstrap-servers=kafka",
                "${KafkaProperties.PREFIX}.receiver.prefetch-batches=2",
                "${KafkaProperties.PREFIX}.receiver.max-deferred-commits=10",
                "${KafkaProperties.PREFIX}.receiver.retry-attempts=5",
                "${KafkaProperties.PREFIX}.receiver.retry-backoff=1s",
                "${KafkaProperties.PREFIX}.receiver.decode-failure-strategy=acknowledge",
            )
            .withUserConfiguration(KafkaAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                val policy = context.getBean(KafkaReceiverPolicy::class.java)
                policy.prefetchBatches.assert().isEqualTo(2)
                policy.maxDeferredCommits.assert().isEqualTo(10)
                val retrySpec = policy.retrySpec as RetryBackoffSpec
                retrySpec.maxAttempts.assert().isEqualTo(5)
                retrySpec.minBackoff.assert().isEqualTo(Duration.ofSeconds(1))
                context.getBean(KafkaRecordDecodeFailureHandler::class.java)
                    .assert()
                    .isSameAs(AcknowledgeKafkaRecordDecodeFailureHandler)
            }
    }
}
