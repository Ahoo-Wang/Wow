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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.reactivestreams.Publisher
import reactor.core.Exceptions
import reactor.core.observability.DefaultSignalListener
import reactor.core.observability.SignalListener
import reactor.core.observability.SignalListenerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import reactor.util.context.ContextView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Instance-scoped entry point for all Wow metrics.
 *
 * A [WowMetrics] instance is bound to exactly one [MeterRegistry]. [NONE]
 * leaves publishers unchanged and never touches a registry.
 */
class WowMetrics private constructor(
    internal val meterRegistry: MeterRegistry?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {
    constructor(meterRegistry: MeterRegistry) : this(meterRegistry, Unit)

    val enabled: Boolean
        get() = meterRegistry != null

    fun <T : Any> operation(
        source: Mono<T>,
        descriptor: MetricDescriptor,
    ): Mono<T> {
        val registry = meterRegistry ?: return source
        return source.tap(OperationMetricsListenerFactory(registry, descriptor, recordItems = false))
    }

    fun <T : Any> operation(
        source: Flux<T>,
        descriptor: MetricDescriptor,
    ): Flux<T> {
        val registry = meterRegistry ?: return source
        return source.tap(OperationMetricsListenerFactory(registry, descriptor, recordItems = true))
    }

    fun <T : Any> stream(
        source: Flux<T>,
        descriptor: MetricDescriptor,
    ): Flux<T> {
        val registry = meterRegistry ?: return source
        return Flux.deferContextual { context ->
            val resolvedDescriptor = descriptor.copy(
                subscriber = context.getMetricsSubscriber() ?: descriptor.subscriber,
            )
            source.tap(StreamMetricsListenerFactory(registry, resolvedDescriptor))
        }
    }

    companion object {
        val NONE = WowMetrics(null, Unit)
    }
}

private class OperationMetricsListenerFactory<T : Any>(
    private val registry: MeterRegistry,
    private val descriptor: MetricDescriptor,
    private val recordItems: Boolean,
) : SignalListenerFactory<T, Unit> {
    override fun initializePublisherState(source: Publisher<out T>) = Unit

    override fun createListener(
        source: Publisher<out T>,
        listenerContext: ContextView,
        publisherContext: Unit,
    ): SignalListener<T> = OperationMetricsListener(registry, descriptor, recordItems)
}

private class OperationMetricsListener<T : Any>(
    private val registry: MeterRegistry,
    private val descriptor: MetricDescriptor,
    private val recordItems: Boolean,
) : DefaultSignalListener<T>() {
    private val sample = Timer.start(registry)
    private val items = AtomicLong()
    private val completed = AtomicBoolean()
    private var error: Throwable? = null

    override fun doOnNext(value: T) {
        items.incrementAndGet()
    }

    override fun doOnError(error: Throwable) {
        this.error = error
    }

    override fun doFinally(terminationType: SignalType) {
        if (!completed.compareAndSet(false, true)) {
            return
        }
        val outcome = terminationType.toMetricOutcome()
        val exception = error.metricException()
        val terminalTags = descriptor.terminalTags(outcome, exception)
        recordSafely {
            sample.stop(registry.timer(WowMetricNames.OPERATION, terminalTags))
        }
        if (recordItems) {
            recordSafely {
                registry.summary(WowMetricNames.OPERATION_ITEMS, terminalTags)
                    .record(items.get().toDouble())
            }
        }
    }

    override fun handleListenerError(listenerError: Throwable) {
        logMetricFailure(listenerError)
    }
}

private class StreamMetricsListenerFactory<T : Any>(
    private val registry: MeterRegistry,
    private val descriptor: MetricDescriptor,
) : SignalListenerFactory<T, Unit> {
    override fun initializePublisherState(source: Publisher<out T>) = Unit

    override fun createListener(
        source: Publisher<out T>,
        listenerContext: ContextView,
        publisherContext: Unit,
    ): SignalListener<T> = StreamMetricsListener(registry, descriptor)
}

private class StreamMetricsListener<T : Any>(
    private val registry: MeterRegistry,
    private val descriptor: MetricDescriptor,
) : DefaultSignalListener<T>() {
    private val completed = AtomicBoolean()
    private val activeSample = createSafely {
        registry.more()
            .longTaskTimer(WowMetricNames.STREAM_ACTIVE, descriptor.baseTags())
            .start()
    }
    private val messages = createSafely {
        registry.counter(WowMetricNames.STREAM_MESSAGES, descriptor.baseTags())
    }
    private var error: Throwable? = null

    override fun doOnNext(value: T) {
        recordSafely { messages?.increment() }
    }

    override fun doOnError(error: Throwable) {
        this.error = error
    }

    override fun doFinally(terminationType: SignalType) {
        if (!completed.compareAndSet(false, true)) {
            return
        }
        recordSafely { activeSample?.stop() }
        val outcome = terminationType.toMetricOutcome()
        val exception = error.metricException()
        recordSafely {
            registry.counter(
                WowMetricNames.STREAM_TERMINATIONS,
                descriptor.terminalTags(outcome, exception),
            ).increment()
        }
    }

    override fun handleListenerError(listenerError: Throwable) {
        logMetricFailure(listenerError)
    }
}

private fun SignalType.toMetricOutcome(): MetricOutcome = when (this) {
    SignalType.ON_ERROR -> MetricOutcome.ERROR
    SignalType.CANCEL -> MetricOutcome.CANCELLED
    else -> MetricOutcome.SUCCESS
}

private fun Throwable?.metricException(): String =
    this?.javaClass?.simpleName?.takeIf(String::isNotBlank) ?: MetricDescriptor.NONE

@Suppress("TooGenericExceptionCaught")
private inline fun recordSafely(record: () -> Unit) {
    try {
        record()
    } catch (failure: Throwable) {
        logMetricFailure(failure)
    }
}

@Suppress("TooGenericExceptionCaught")
private inline fun <T> createSafely(create: () -> T): T? = try {
    create()
} catch (failure: Throwable) {
    logMetricFailure(failure)
    null
}

private fun logMetricFailure(failure: Throwable) {
    Exceptions.throwIfFatal(failure)
    METRICS_LOG.warn(failure) { "Failed to record Wow metrics." }
}

private val METRICS_LOG = KotlinLogging.logger {}
