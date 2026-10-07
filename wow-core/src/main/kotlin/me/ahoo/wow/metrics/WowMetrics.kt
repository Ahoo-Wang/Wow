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
import io.micrometer.core.instrument.Gauge
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Instance-scoped entry point for all Wow metrics.
 *
 * A [WowMetrics] instance is bound to exactly one [MeterRegistry]. [NONE]
 * leaves publishers unchanged and never touches a registry.
 */
class WowMetrics(
    internal val meterRegistry: MeterRegistry? = null,
) {

    val enabled: Boolean
        get() = meterRegistry != null

    private val meters: MeterCache? = meterRegistry?.let(::MeterCache)

    fun <T : Any> operation(
        source: Mono<T>,
        descriptor: MetricDescriptor,
    ): Mono<T> {
        val cache = meters ?: return source
        return source.tap(OperationMetricsListenerFactory(cache, descriptor, recordItems = false))
    }

    fun <T : Any> operation(
        source: Flux<T>,
        descriptor: MetricDescriptor,
    ): Flux<T> {
        val cache = meters ?: return source
        return source.tap(OperationMetricsListenerFactory(cache, descriptor, recordItems = true))
    }

    fun <T : Any> stream(
        source: Flux<T>,
        descriptor: MetricDescriptor,
    ): Flux<T> {
        val cache = meters ?: return source
        return Flux.deferContextual { context ->
            val resolvedDescriptor = descriptor.copy(
                subscriber = context.getMetricsSubscriber() ?: descriptor.subscriber,
            )
            source.tap(StreamMetricsListenerFactory(cache, resolvedDescriptor))
        }
    }

    /**
     * Counts one [outcome] of the processing of an event by a function (`wow.processing.outcomes`, tagged with
     * [descriptor] and `outcome`).
     */
    fun processingOutcome(descriptor: MetricDescriptor, outcome: String) {
        val cache = meters ?: return
        recordSafely {
            cache.of(descriptor).processingOutcome(outcome).increment()
        }
    }

    /**
     * Registers the gauge [name] tagged with [descriptor], reading [value]; it holds a strong reference to [value].
     */
    fun gauge(name: String, descriptor: MetricDescriptor, value: () -> Number) {
        val registry = meterRegistry ?: return
        recordSafely {
            Gauge.builder(name) { value().toDouble() }
                .tags(descriptor.baseTags())
                .strongReference(true)
                .register(registry)
        }
    }

    companion object {
        val NONE = WowMetrics()
    }
}

private class OperationMetricsListenerFactory<T : Any>(
    private val meters: MeterCache,
    private val descriptor: MetricDescriptor,
    private val recordItems: Boolean,
) : SignalListenerFactory<T, Unit> {
    override fun initializePublisherState(source: Publisher<out T>) = Unit

    override fun createListener(
        source: Publisher<out T>,
        listenerContext: ContextView,
        publisherContext: Unit,
    ): SignalListener<T> = OperationMetricsListener(meters, descriptor, recordItems)
}

private class OperationMetricsListener<T : Any>(
    private val meters: MeterCache,
    private val descriptor: MetricDescriptor,
    private val recordItems: Boolean,
) : DefaultSignalListener<T>() {
    private val sample = Timer.start(meters.registry)
    private val items = AtomicLong()
    private var error: Throwable? = null

    override fun doOnNext(value: T) {
        items.incrementAndGet()
    }

    override fun doOnError(error: Throwable) {
        this.error = error
    }

    override fun doFinally(terminationType: SignalType) {
        val terminal = createSafely {
            meters.of(descriptor).terminal(terminationType.toMetricOutcome(), error.metricException())
        } ?: return
        recordSafely {
            sample.stop(terminal.operation())
        }
        if (recordItems) {
            recordSafely {
                terminal.operationItems().record(items.get().toDouble())
            }
        }
    }
}

private class StreamMetricsListenerFactory<T : Any>(
    private val meters: MeterCache,
    private val descriptor: MetricDescriptor,
) : SignalListenerFactory<T, Unit> {
    override fun initializePublisherState(source: Publisher<out T>) = Unit

    override fun createListener(
        source: Publisher<out T>,
        listenerContext: ContextView,
        publisherContext: Unit,
    ): SignalListener<T> = StreamMetricsListener(meters.of(descriptor))
}

private class StreamMetricsListener<T : Any>(
    private val meters: DescriptorMeters,
) : DefaultSignalListener<T>() {
    private val activeSample = createSafely {
        meters.streamActive().start()
    }
    private val messages = createSafely {
        meters.streamMessages()
    }
    private var error: Throwable? = null

    override fun doOnNext(value: T) {
        recordSafely { messages?.increment() }
    }

    override fun doOnError(error: Throwable) {
        this.error = error
    }

    override fun doFinally(terminationType: SignalType) {
        recordSafely { activeSample?.stop() }
        recordSafely {
            meters.terminal(terminationType.toMetricOutcome(), error.metricException())
                .streamTerminations()
                .increment()
        }
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
