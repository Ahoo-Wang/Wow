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

package me.ahoo.wow.spring.boot.starter.opentelemetry

import me.ahoo.wow.opentelemetry.aggregate.TraceAggregateFilter
import me.ahoo.wow.opentelemetry.eventprocessor.TraceEventProcessorFilter
import me.ahoo.wow.opentelemetry.projection.TraceProjectionFilter
import me.ahoo.wow.opentelemetry.saga.TraceStatelessSagaFilter
import me.ahoo.wow.opentelemetry.snapshot.TraceSnapshotFilter
import me.ahoo.wow.spring.boot.starter.enableWow
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class WowOpenTelemetryAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withUserConfiguration(
                WowOpenTelemetryAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(TraceAggregateFilter::class.java)
                    .hasSingleBean(TraceProjectionFilter::class.java)
                    .hasSingleBean(TraceSnapshotFilter::class.java)
                    .hasSingleBean(TraceStatelessSagaFilter::class.java)
                    .hasSingleBean(TraceEventProcessorFilter::class.java)
            }
    }
}
