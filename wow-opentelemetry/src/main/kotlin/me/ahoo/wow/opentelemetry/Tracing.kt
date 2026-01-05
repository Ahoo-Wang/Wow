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

package me.ahoo.wow.opentelemetry

import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.opentelemetry.eventsourcing.TracingEventStore
import me.ahoo.wow.opentelemetry.messaging.TracingDistributedCommandBus
import me.ahoo.wow.opentelemetry.messaging.TracingDistributedEventBus
import me.ahoo.wow.opentelemetry.messaging.TracingDistributedStateEventBus
import me.ahoo.wow.opentelemetry.messaging.TracingLocalCommandBus
import me.ahoo.wow.opentelemetry.messaging.TracingLocalEventBus
import me.ahoo.wow.opentelemetry.messaging.TracingLocalStateEventBus
import me.ahoo.wow.opentelemetry.messaging.TracingMessageBus
import me.ahoo.wow.opentelemetry.snapshot.TracingSnapshotRepository
import me.ahoo.wow.opentelemetry.wait.TracingWaitStrategy

object Tracing {

    fun LocalCommandBus.tracing(): LocalCommandBus {
        return tracing {
            TracingLocalCommandBus(this)
        }
    }

    fun DistributedCommandBus.tracing(): DistributedCommandBus {
        return tracing {
            TracingDistributedCommandBus(this)
        }
    }

    fun LocalDomainEventBus.tracing(): LocalDomainEventBus {
        return tracing {
            TracingLocalEventBus(this)
        }
    }

    fun DistributedDomainEventBus.tracing(): DistributedDomainEventBus {
        return tracing {
            TracingDistributedEventBus(this)
        }
    }

    fun EventStore.tracing(): EventStore {
        return tracing {
            TracingEventStore(this)
        }
    }

    fun SnapshotRepository.tracing(): SnapshotRepository {
        return tracing {
            TracingSnapshotRepository(this)
        }
    }

    fun LocalStateEventBus.tracing(): LocalStateEventBus {
        return tracing {
            TracingLocalStateEventBus(this)
        }
    }

    fun DistributedStateEventBus.tracing(): DistributedStateEventBus {
        return tracing {
            TracingDistributedStateEventBus(this)
        }
    }

    fun WaitStrategy.tracing(): WaitStrategy {
        return tracing {
            TracingWaitStrategy(this)
        }
    }

    fun <T : Any> T.tracing(): Any {
        return when (this) {
            is LocalCommandBus -> tracing()
            is DistributedCommandBus -> tracing()
            is LocalDomainEventBus -> tracing()
            is DistributedDomainEventBus -> tracing()
            is EventStore -> tracing()
            is SnapshotRepository -> tracing()
            is LocalStateEventBus -> tracing()
            is DistributedStateEventBus -> tracing()
            is WaitStrategy -> tracing()
            else -> this
        }
    }

    inline fun <T> T.tracing(block: (T) -> T): T {
        if (this is TracingMessageBus<*, *, *>) {
            return this
        }
        return block(this)
    }
}
