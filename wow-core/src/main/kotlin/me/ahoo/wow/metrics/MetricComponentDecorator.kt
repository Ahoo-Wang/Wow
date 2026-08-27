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

import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.MessageSubscription

internal interface Metered

internal abstract class MetricComponentDecorator<T : Any>(
    final override val delegate: T,
    protected val metrics: WowMetrics,
    protected val source: String,
) : Decorator<T>,
    Metered {
    init {
        require(source.isNotBlank()) { "source must not be blank." }
    }

    protected fun descriptor(
        component: String,
        operation: String,
        context: String = MetricDescriptor.NONE,
        aggregate: String = MetricDescriptor.NONE,
        message: String = MetricDescriptor.NONE,
        processor: String = MetricDescriptor.NONE,
        subscriber: String = MetricDescriptor.NONE,
    ): MetricDescriptor = MetricDescriptor(
        component = component,
        operation = operation,
        context = context,
        aggregate = aggregate,
        message = message,
        processor = processor,
        source = source,
        subscriber = subscriber,
    )
}

internal fun MessageSubscription.metricContext(): String = when (namedAggregates.size) {
    0 -> MetricDescriptor.NONE
    1 -> namedAggregates.first().contextName
    else -> MetricDescriptor.MULTIPLE
}

internal fun MessageSubscription.metricAggregate(): String = when (namedAggregates.size) {
    0 -> MetricDescriptor.NONE
    1 -> namedAggregates.first().aggregateName
    else -> MetricDescriptor.MULTIPLE
}
