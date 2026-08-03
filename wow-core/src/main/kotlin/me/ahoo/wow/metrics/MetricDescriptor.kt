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

package me.ahoo.wow.metrics

import io.micrometer.core.instrument.Tags

/** Low-cardinality identity shared by all Wow metric families. */
data class MetricDescriptor(
    val component: String,
    val operation: String,
    val context: String = NONE,
    val aggregate: String = NONE,
    val message: String = NONE,
    val processor: String = NONE,
    val source: String = NONE,
    val subscriber: String = NONE,
) {
    init {
        require(component.isNotBlank()) { "component must not be blank." }
        require(operation.isNotBlank()) { "operation must not be blank." }
        require(context.isNotBlank()) { "context must not be blank." }
        require(aggregate.isNotBlank()) { "aggregate must not be blank." }
        require(message.isNotBlank()) { "message must not be blank." }
        require(processor.isNotBlank()) { "processor must not be blank." }
        require(source.isNotBlank()) { "source must not be blank." }
        require(subscriber.isNotBlank()) { "subscriber must not be blank." }
    }

    internal fun baseTags(): Tags = Tags.of(
        COMPONENT_TAG,
        component,
        OPERATION_TAG,
        operation,
        CONTEXT_TAG,
        context,
        AGGREGATE_TAG,
        aggregate,
        MESSAGE_TAG,
        message,
        PROCESSOR_TAG,
        processor,
        SOURCE_TAG,
        source,
        SUBSCRIBER_TAG,
        subscriber,
    )

    internal fun terminalTags(
        outcome: MetricOutcome,
        exception: String,
    ): Tags = baseTags()
        .and(OUTCOME_TAG, outcome.metricValue)
        .and(EXCEPTION_TAG, exception)

    companion object {
        const val NONE = "none"
        const val MULTIPLE = "multiple"

        internal const val COMPONENT_TAG = "component"
        internal const val OPERATION_TAG = "operation"
        internal const val CONTEXT_TAG = "context"
        internal const val AGGREGATE_TAG = "aggregate"
        internal const val MESSAGE_TAG = "message"
        internal const val PROCESSOR_TAG = "processor"
        internal const val SOURCE_TAG = "source"
        internal const val SUBSCRIBER_TAG = "subscriber"
        internal const val OUTCOME_TAG = "outcome"
        internal const val EXCEPTION_TAG = "exception"
    }
}

internal enum class MetricOutcome(
    val metricValue: String,
) {
    SUCCESS("success"),
    ERROR("error"),
    CANCELLED("cancelled"),
}

internal object WowMetricNames {
    const val OPERATION = "wow.operation"
    const val OPERATION_ITEMS = "wow.operation.items"
    const val STREAM_ACTIVE = "wow.stream.active"
    const val STREAM_MESSAGES = "wow.stream.messages"
    const val STREAM_TERMINATIONS = "wow.stream.terminations"
}
