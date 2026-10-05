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

package me.ahoo.wow.eventsourcing.snapshot.dispatcher

import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.Handler
import me.ahoo.wow.filter.LogResumeErrorHandler
import me.ahoo.wow.metrics.MetricDescriptor
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.processing.failure.FailureRecorder
import me.ahoo.wow.processing.failure.FailureRecordingHandler

interface SnapshotHandler : Handler<StateEventExchange<*>>

/**
 * The snapshot handler. The snapshot function acknowledges its state event itself ([SnapshotFunctionFilter]) before
 * a failure reaches this handler, so a failure is recorded with [failureRecorder], but an outcome that would withhold
 * the acknowledgement has no effect: the state event is already acknowledged.
 */
class DefaultSnapshotHandler(
    chain: FilterChain<StateEventExchange<*>>,
    errorHandler: ErrorHandler<StateEventExchange<*>> = LogResumeErrorHandler(),
    failureRecorder: FailureRecorder = FailureRecorder.NONE,
    metrics: WowMetrics = WowMetrics.NONE,
) : SnapshotHandler, FailureRecordingHandler<StateEventExchange<*>>(
    chain = chain,
    errorHandler = errorHandler,
    failureRecorder = failureRecorder,
    metrics = metrics,
) {
    override fun metricDescriptor(context: StateEventExchange<*>): MetricDescriptor =
        MetricDescriptor(
            component = "snapshot_handler",
            operation = "process",
            context = context.message.contextName,
            aggregate = context.message.aggregateName,
        )
}
