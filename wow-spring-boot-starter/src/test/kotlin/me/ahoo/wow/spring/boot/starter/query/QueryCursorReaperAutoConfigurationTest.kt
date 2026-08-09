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

@file:OptIn(
    me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.spring.boot.starter.query

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.cursor.QueryCursorLeaseConfiguration
import me.ahoo.wow.query.gateway.QueryGatewayRuntime
import me.ahoo.wow.spring.boot.starter.enableWow
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.SmartLifecycle
import reactor.core.publisher.Mono
import java.time.Duration

class QueryCursorReaperAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .enableWow()
        .withConfiguration(AutoConfigurations.of(QueryCursorReaperAutoConfiguration::class.java))

    @Test
    fun `reaper should remain disabled by default`() {
        contextRunner.run { context ->
            context.assert().hasNotFailed()
            context.containsBean(QueryCursorReaperAutoConfiguration.QUERY_CURSOR_REAPER_LIFECYCLE_BEAN_NAME)
                .assert().isFalse()
        }
    }

    @Test
    fun `enabled reaper should require a cursor lease configuration`() {
        contextRunner
            .withPropertyValues("${QueryCursorReaperProperties.PREFIX}.enabled=true")
            .withBean(QueryGatewayRuntime::class.java, ::runtime)
            .run { context ->
                context.assert().hasFailed()
                generateSequence(context.startupFailure, Throwable::cause)
                    .mapNotNull(Throwable::message)
                    .joinToString("\n")
                    .assert().contains(QueryCursorLeaseConfiguration::class.java.name)
            }
    }

    @Test
    fun `enabled reaper should bind policy and own one running lifecycle`() {
        contextRunner
            .withPropertyValues(
                "${QueryCursorReaperProperties.PREFIX}.enabled=true",
                "${QueryCursorReaperProperties.PREFIX}.initial-delay=PT1H",
                "${QueryCursorReaperProperties.PREFIX}.interval=PT2H",
                "${QueryCursorReaperProperties.PREFIX}.batch-size=7",
                "${QueryCursorReaperProperties.PREFIX}.max-batches-per-run=3",
            )
            .withBean(QueryGatewayRuntime::class.java, ::runtime)
            .withBean(QueryCursorLeaseConfiguration::class.java, { mockk() })
            .run { context ->
                context.assert().hasNotFailed()
                val lifecycle = context.getBean(
                    QueryCursorReaperAutoConfiguration.QUERY_CURSOR_REAPER_LIFECYCLE_BEAN_NAME,
                    SmartLifecycle::class.java,
                )
                lifecycle.isRunning.assert().isTrue()
                val properties = context.getBean(QueryCursorReaperProperties::class.java)
                properties.initialDelay.assert().isEqualTo(Duration.ofHours(1))
                properties.interval.assert().isEqualTo(Duration.ofHours(2))
                properties.batchSize.assert().isEqualTo(7)
                properties.maxBatchesPerRun.assert().isEqualTo(3)
            }
    }

    private fun runtime(): QueryGatewayRuntime = mockk {
        every { reapExpiredQueryCursors(any()) } returns Mono.just(0)
    }
}
