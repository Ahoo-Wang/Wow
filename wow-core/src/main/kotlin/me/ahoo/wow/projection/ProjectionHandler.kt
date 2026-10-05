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

package me.ahoo.wow.projection

import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.dispatcher.EventHandler
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.LogResumeErrorHandler
import me.ahoo.wow.metrics.MetricDescriptor
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.processing.failure.FailureRecorder
import me.ahoo.wow.processing.failure.FailureRecordingHandler

/**
 * Handler interface for projections that processes domain events.
 * Implementations of this interface handle the execution of projection logic in response to domain events.
 */
interface ProjectionHandler : EventHandler

/**
 * Default implementation of [ProjectionHandler] that uses a filter chain for processing.
 * This handler applies a series of filters to domain event exchanges and handles errors gracefully.
 *
 * @param chain The filter chain to apply to domain event exchanges.
 * @param errorHandler The error handler for processing failures (default: [LogResumeErrorHandler]).
 * @param failureRecorder Records failures durably (default: none).
 * @param ackOnUnrecordedFailure Whether a failure no recorder recorded is acknowledged (default: true).
 * @param metrics Counts the processing outcomes (default: none).
 */
class DefaultProjectionHandler(
    chain: FilterChain<DomainEventExchange<*>>,
    errorHandler: ErrorHandler<DomainEventExchange<*>> = LogResumeErrorHandler(),
    failureRecorder: FailureRecorder = FailureRecorder.NONE,
    ackOnUnrecordedFailure: Boolean = true,
    metrics: WowMetrics = WowMetrics.NONE,
) : FailureRecordingHandler<DomainEventExchange<*>>(
    chain = chain,
    errorHandler = errorHandler,
    failureRecorder = failureRecorder,
    ackOnUnrecordedFailure = ackOnUnrecordedFailure,
    metrics = metrics,
),
    ProjectionHandler {
    override fun metricDescriptor(context: DomainEventExchange<*>): MetricDescriptor =
        MetricDescriptor(
            component = "projection_handler",
            operation = "process",
            context = context.message.contextName,
            aggregate = context.message.aggregateName,
            message = context.message.name,
            processor = context.getFunction()?.processorName ?: MetricDescriptor.NONE,
        )
}
