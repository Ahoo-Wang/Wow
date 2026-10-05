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

package me.ahoo.wow.runtime

import me.ahoo.wow.runtime.internal.DefaultRuntimeExecutionResources
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A storage or transport resource that holds accepted work: a batch writer's pending windows, a broker producer's
 * buffered records. The runtime flushes it after every component has stopped, within the same shutdown deadline
 * ([RuntimeResources]), so the work the dispatchers accepted is written before the process exits.
 */
interface RuntimeResource {
    /**
     * Stops accepting work and completes once the accepted work is written. The runtime subscribes on its lifecycle
     * threads, so a blocking flush is allowed. After a force stop the runtime no longer waits for it.
     */
    fun stopGracefully(): Mono<Void>

    /** Releases the resource promptly without blocking; unwritten work fails. Idempotent. */
    fun forceStop()

    companion object {
        /** A resource with nothing to flush. */
        @JvmField
        val NONE: RuntimeResource = object : RuntimeResource {
            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() = Unit

            override fun toString(): String = "RuntimeResource.NONE"
        }
    }
}

/**
 * The runtime component that owns the [RuntimeResource]s. Register it **first**, so it prepares first and stops
 * last: the runtime stops components in reverse order, so the resources are flushed after every dispatcher has
 * drained, under the runtime's one `shutdownTimeout`. The resources are resolved when the runtime prepares, and
 * flushed concurrently.
 */
class RuntimeResources internal constructor(
    private val resolve: () -> Iterable<RuntimeResource>,
    private val scheduler: Scheduler,
) : RuntimeComponent {
    constructor(resolve: () -> Iterable<RuntimeResource>) : this(
        resolve,
        DefaultRuntimeExecutionResources.shutdownScheduler,
    )

    constructor(resources: Iterable<RuntimeResource>) : this({ resources })

    @Volatile
    private var resources: List<RuntimeResource> = emptyList()

    /** The resources resolved at [prepare], each once. */
    val resolved: List<RuntimeResource>
        get() = resources

    override fun prepare(runtimeContext: RuntimeContext): Mono<Void> = Mono.fromRunnable {
        val distinct = Collections.newSetFromMap(IdentityHashMap<RuntimeResource, Boolean>())
        resources = resolve().filter { it !== RuntimeResource.NONE && distinct.add(it) }
    }

    override fun start() = Unit

    override fun stopGracefully(): Mono<Void> = Mono.defer {
        Mono.whenDelayError(
            resources.map { resource ->
                Mono.defer(resource::stopGracefully).subscribeOn(scheduler)
            },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    override fun forceStop() {
        var failure: Throwable? = null
        resources.forEach { resource ->
            try {
                resource.forceStop()
            } catch (error: Throwable) {
                Exceptions.throwIfFatal(error)
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }
}
