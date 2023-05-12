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
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * OpenTelemetry Auto Configuration .
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnWowEnabled
@ConditionalOnOpenTelemetryEnabled
class WowOpenTelemetryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun traceAggregateFilter(): TraceAggregateFilter {
        return TraceAggregateFilter
    }

    @Bean
    @ConditionalOnMissingBean
    fun traceProjectionFilter(): TraceProjectionFilter {
        return TraceProjectionFilter
    }

    @Bean
    fun traceSnapshotFilter(): TraceSnapshotFilter {
        return TraceSnapshotFilter
    }

    @Bean
    fun traceStatelessSagaFilter(): TraceStatelessSagaFilter {
        return TraceStatelessSagaFilter
    }

    @Bean
    fun traceEventProcessorFilter(): TraceEventProcessorFilter {
        return TraceEventProcessorFilter
    }

    @Bean
    fun tracingBeanPostProcessor(commandProperties: CommandProperties,): TracingBeanPostProcessor {
        return TracingBeanPostProcessor(commandProperties)
    }
}
