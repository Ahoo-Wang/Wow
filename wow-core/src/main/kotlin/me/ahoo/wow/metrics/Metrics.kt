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

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventHandler
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotHandler
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.modeling.command.CommandHandler
import me.ahoo.wow.projection.ProjectionHandler
import me.ahoo.wow.saga.stateless.StatelessSagaHandler
import reactor.core.publisher.Flux
import reactor.util.context.Context
import reactor.util.context.ContextView
import kotlin.jvm.optionals.getOrNull

@Suppress("TooManyFunctions")
object Metrics {
    const val AGGREGATE_KEY = "aggregate"
    const val SUBSCRIBER_CONTEXT_KEY = "(MetricsSubscriber)"
    const val SUBSCRIBER_KEY = "subscriber"
    const val COMMAND_KEY = "command"
    const val SOURCE_KEY = "source"
    const val EVENT_KEY = "event"
    const val PROCESSOR_KEY = "processor"
    val enabled = System.getProperty("wow.metrics.enabled", "true").toBoolean()

    fun ContextView.getMetricsSubscriber(): String? {
        return getOrEmpty<String>(SUBSCRIBER_CONTEXT_KEY).getOrNull()
    }

    fun Context.setMetricsSubscriber(metricsSubscriber: String): Context {
        return this.put(SUBSCRIBER_CONTEXT_KEY, metricsSubscriber)
    }

    fun <T> Flux<T>.writeMetricsSubscriber(metricsSubscriber: String): Flux<T> {
        return contextWrite {
            it.setMetricsSubscriber(metricsSubscriber)
        }
    }

    fun <T> Flux<T>.tagMetricsSubscriber(): Flux<T> {
        return Flux.deferContextual {
            val metricsSubscriber = it.getMetricsSubscriber() ?: return@deferContextual this.metrics()
            tag(SUBSCRIBER_KEY, metricsSubscriber).metrics()
        }
    }

    fun CommandBus.metrizable(): CommandBus {
        return metrizable {
            MetricCommandBus(this)
        }
    }

    fun DomainEventBus.metrizable(): DomainEventBus {
        return metrizable {
            MetricDomainEventBus(this)
        }
    }

    fun EventStore.metrizable(): EventStore {
        return metrizable {
            MetricEventStore(this)
        }
    }

    fun SnapshotStrategy.metrizable(): SnapshotStrategy {
        return metrizable {
            MetricSnapshotStrategy(this)
        }
    }

    fun SnapshotRepository.metrizable(): SnapshotRepository {
        return metrizable {
            MetricSnapshotRepository(this)
        }
    }

    fun CommandHandler.metrizable(): CommandHandler {
        return metrizable {
            MetricCommandHandler(this)
        }
    }

    fun SnapshotHandler.metrizable(): SnapshotHandler {
        return metrizable {
            MetricSnapshotHandler(this)
        }
    }

    fun DomainEventHandler.metrizable(): DomainEventHandler {
        return metrizable {
            MetricDomainEventHandler(this)
        }
    }

    fun StatelessSagaHandler.metrizable(): StatelessSagaHandler {
        return metrizable {
            MetricStatelessSagaHandler(this)
        }
    }

    fun ProjectionHandler.metrizable(): ProjectionHandler {
        return metrizable {
            MetricProjectionHandler(this)
        }
    }

    fun <T : Any> T.metrizable(): Any {
        return when (this) {
            is LocalFirstCommandBus -> this
            is CommandGateway -> this
            is CommandBus -> metrizable()
            is DomainEventBus -> metrizable()
            is EventStore -> metrizable()
            is SnapshotStrategy -> metrizable()
            is SnapshotRepository -> metrizable()
            is CommandHandler -> metrizable()
            is SnapshotHandler -> metrizable()
            is DomainEventHandler -> metrizable()
            is StatelessSagaHandler -> metrizable()
            is ProjectionHandler -> metrizable()
            else -> this
        }
    }

    inline fun <T> T.metrizable(block: (T) -> T): T {
        if (!enabled) {
            return this
        }
        if (this is Metrizable) {
            return this
        }
        return block(this)
    }
}
