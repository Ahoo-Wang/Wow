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

package me.ahoo.wow.spring.boot

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.internal.InternalPort
import me.ahoo.wow.http.query.QueryStreams
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The phases of the host's lifecycles (decisions D71, D108; plan P17). Spring starts the phases in ascending order
 * and stops them in descending order, so a node
 *
 * - starts: [BACKENDS_PHASE] (the stores' schemas) → [INTERNAL_SERVER_PHASE] (the internal port, and the node's
 *   address when the system chose the port) → [NODE_PHASE]
 *   ([ServiceNode.start]) → [PUBLIC_SERVER_PHASE] (Spring Boot's web server) → readiness `ACCEPTING_TRAFFIC`, which
 *   Spring Boot reports once the application has started;
 * - stops: readiness `REFUSING_TRAFFIC` (Spring Boot, as the context closes) and the public server's graceful
 *   shutdown → [NODE_PHASE] ([ServiceNode.stop]: hand-off, drain, waits, close) → [INTERNAL_SERVER_PHASE], which
 *   serves the other nodes' forwards and signals until then → [ROUTES_SCOPE_PHASE], which cancels what the routes
 *   still stream → the backends, closed with their beans.
 *
 * Spring Boot bounds each phase's stop by `spring.lifecycle.timeout-per-shutdown-phase`, which the host raises to
 * `wow.wait.max-timeout + wow.cluster.drain-timeout` ([ShutdownTimeoutEnvironmentPostProcessor]).
 */
internal object WowLifecycles {
    /** The phase of Spring Boot's web server start and stop (`WebServerStartStopLifecycle`). */
    const val PUBLIC_SERVER_PHASE: Int = SmartLifecycle.DEFAULT_PHASE - 2048

    /** [ServiceNodeLifecycle]: before the public server starts, after it stops. */
    const val NODE_PHASE: Int = PUBLIC_SERVER_PHASE - 1024

    /** [InternalServerLifecycle]: before the node starts, after it stops. */
    const val INTERNAL_SERVER_PHASE: Int = NODE_PHASE - 1024

    /** [RoutesScope]: stops after the internal server, before the backends close. */
    const val ROUTES_SCOPE_PHASE: Int = INTERNAL_SERVER_PHASE - 256

    /** [BackendsLifecycle]: first of all. */
    const val BACKENDS_PHASE: Int = INTERNAL_SERVER_PHASE - 1024

    /** [QueryIndexesLifecycle]: after the backends' schemas exist, before the internal server starts. */
    const val QUERY_INDEXES_PHASE: Int = BACKENDS_PHASE + 512

    /**
     * [QueryStreamsLifecycle]: stops before the public server's graceful shutdown (`DEFAULT_PHASE - 1024`), which
     * waits for the open requests, so a query stream ends at once rather than at its hold time.
     */
    const val QUERY_STREAMS_PHASE: Int = SmartLifecycle.DEFAULT_PHASE - 512

    /**
     * [GracefulShutdownSettled]: stops after the public server's graceful shutdown (`DEFAULT_PHASE - 1024`) and before
     * its stop ([PUBLIC_SERVER_PHASE]).
     */
    const val GRACEFUL_SHUTDOWN_SETTLED_PHASE: Int = SmartLifecycle.DEFAULT_PHASE - 1536
}

/**
 * Waits, between the public server's graceful shutdown and its stop, for the thread of that shutdown to end, at most
 * [timeout] (review X-4): a workaround of a race in Spring Boot 4.1.1 (`spring-boot-reactor-netty`, and 3.5.x before
 * it), to remove once the host builds on a Spring Boot that fixes it (below).
 *
 * The race, in `org.springframework.boot.reactor.netty.GracefulShutdown`
 * (https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-reactor-netty/src/main/java/org/springframework/boot/reactor/netty/GracefulShutdown.java):
 * `doShutdown`, on the `netty-shutdown` thread, calls `callback.shutdownComplete(...)` and only then, in `finally`,
 * sets `shutdownThread = null` and `shuttingDown = false`. The callback lets the lifecycle go on to the server's stop
 * (`WebServerStartStopLifecycle`, the next phase), which calls `abort()`: it reads `shutdownThread`, then polls
 * `while (!shuttingDown) sleep(50)`. When the `finally` runs between the two, `abort()` polls forever, and the
 * context's close hangs there with no Wow frame on its stack (seen as `PaymentServerTest.close` stuck in
 * `GracefulShutdown.abort`). Fixed upstream in Spring Boot 4.1.2
 * (https://github.com/spring-projects/spring-boot/issues/52196; 4.0.9 for the 4.0 line,
 * https://github.com/spring-projects/spring-boot/issues/52119): remove this lifecycle and its bean once the host
 * builds on 4.1.2 or later.
 *
 * Once that thread has ended, `abort()` finds none and returns at once. When the graceful shutdown outlasted its
 * phase, the thread is still shutting down after [timeout], which is logged as a warning, and `abort()` interrupts it.
 */
@InternalWowApi
class GracefulShutdownSettled @JvmOverloads constructor(
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val threads: () -> Collection<Thread> = { Thread.getAllStackTraces().keys },
) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        threads().filter { it.name == SHUTDOWN_THREAD && it.isAlive }.forEach { shutdown ->
            val left = (deadline - System.nanoTime()) / NANOS_PER_MILLI
            if (left > 0) shutdown.join(left)
            if (shutdown.isAlive) {
                logger.warn {
                    "The public server's graceful shutdown is still running after $timeout: the server stops it now."
                }
            }
        }
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = WowLifecycles.GRACEFUL_SHUTDOWN_SETTLED_PHASE

    @InternalWowApi
    companion object {
        /** The name of the thread Spring Boot's Reactor Netty graceful shutdown runs on. */
        const val SHUTDOWN_THREAD: String = "netty-shutdown"

        /** How long the server's stop waits for that thread at most. */
        val DEFAULT_TIMEOUT: Duration = 5.seconds

        private const val NANOS_PER_MILLI = 1_000_000L
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * Ends every open query stream of the public port with `end` `{"reason":"node-stopping","resumable":true}`, and refuses
 * new ones, as the node starts to stop (decision D253): its clients resume them on another node.
 */
@InternalWowApi
class QueryStreamsLifecycle(private val streams: QueryStreams) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
        streams.close()
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = WowLifecycles.QUERY_STREAMS_PHASE
}

/**
 * The scope the HTTP routes, public and internal, produce their streamed answers in, apart from the requests: cancelled
 * once the node and the internal port have stopped, so a stream still open then ends instead of outliving the node.
 */
@InternalWowApi
class RoutesScope : SmartLifecycle {
    /** The routes' scope. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var running = false

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
        scope.cancel("The node stopped.")
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = WowLifecycles.ROUTES_SCOPE_PHASE
}

/** Creates the schemas of the backends the node opened, where they are missing, before anything uses them. */
@InternalWowApi
class BackendsLifecycle(private val backends: HostBackends) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        backends.createSchemas()
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = WowLifecycles.BACKENDS_PHASE
}

/**
 * Serves the internal port from before the node starts until after it stops; once the port is bound, tells [bound]
 * the port it is bound at, so a node whose port the system chose learns its address ([ServiceNode.bind]).
 */
@InternalWowApi
class InternalServerLifecycle(
    private val server: InternalPort,
    private val bound: (port: Int) -> Unit = {},
) : SmartLifecycle {
    override fun start() {
        server.start()
        bound(server.boundPort)
    }

    override fun stop() {
        server.stop()
    }

    override fun isRunning(): Boolean = server.isRunning

    override fun getPhase(): Int = WowLifecycles.INTERNAL_SERVER_PHASE
}

/**
 * Starts the [node] once its backends and internal port are up, and stops it once the public server has stopped:
 * a stop reports the application not ready first, when nothing did (a host without a public server), then runs
 * [ServiceNode.stop] apart from the thread that closes the context, which waits for it at most the phase's timeout.
 */
@InternalWowApi
class ServiceNodeLifecycle(
    private val node: ServiceNode,
    private val events: ApplicationEventPublisher,
    private val standby: Boolean = false,
    private val availability: () -> ApplicationAvailability?,
) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        runBlocking { node.start(standby) }
        running = true
    }

    override fun stop() {
        refuseTraffic()
        running = false
        runBlocking { node.stop() }
    }

    override fun stop(callback: Runnable) {
        refuseTraffic()
        running = false
        thread(name = "wow-node-stop-${node.node}") {
            try {
                runBlocking { node.stop() }
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                logger.error(failure) { "Node ${node.node} of ${node.service} failed to stop cleanly." }
            } finally {
                callback.run()
            }
        }
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = WowLifecycles.NODE_PHASE

    private fun refuseTraffic() {
        if (availability()?.readinessState != ReadinessState.REFUSING_TRAFFIC) {
            AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC)
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
