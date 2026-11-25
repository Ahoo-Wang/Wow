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

package me.ahoo.wow.spring.boot.node

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
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spring.boot.WowProperties
import me.ahoo.wow.spring.boot.query.QueryIndexesLifecycle
import me.ahoo.wow.spring.boot.store.HostBackends
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import org.springframework.context.support.DefaultLifecycleProcessor
import org.springframework.core.env.Environment
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * The phases of the host's lifecycles (plan P17). Spring starts the phases in ascending order
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
 * Spring Boot bounds each phase's stop by `spring.lifecycle.timeout-per-shutdown-phase`, which the host raises to the
 * node's stop bound ([me.ahoo.wow.runtime.node.ServiceNodeOptions.stopBound], [ShutdownTimeoutEnvironmentPostProcessor]);
 * the public server's graceful shutdown, by the longest wait a request holds ([GracefulShutdownBound]).
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
     * The phase of Spring Boot's graceful shutdown of the web server (`WebServerGracefulShutdownLifecycle`), which
     * [GracefulShutdownBound] bounds: after the query streams end, before the public server stops.
     */
    const val GRACEFUL_SHUTDOWN_PHASE: Int = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE
}

/**
 * Bounds the public server's graceful shutdown ([WowLifecycles.GRACEFUL_SHUTDOWN_PHASE]) at [bound] (#6958). Spring
 * Boot's graceful shutdown waits for every request still open, one whose body stalls or whose reader stops reading
 * included, until its phase's timeout, which the host raises to the node's stop bound for the node's own phase. This
 * phase gets its own: the longest wait a request holds, `wow.wait.max-timeout`, and [MARGIN] for its answer to be
 * written, or the phase's timeout when that is shorter ([of]). Past it, the server's stop cuts what is still open:
 * Spring Boot's `NettyWebServer.stop` aborts the graceful shutdown and disposes the server, within Reactor Netty's 3
 * seconds.
 */
@InternalWowApi
class GracefulShutdownBound(val bound: Duration) : BeanPostProcessor {
    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (bean is DefaultLifecycleProcessor) {
            bean.setTimeoutForShutdownPhase(WowLifecycles.GRACEFUL_SHUTDOWN_PHASE, bound.inWholeMilliseconds)
        }
        return bean
    }

    @InternalWowApi
    companion object {
        /** The time past the longest wait given to its answer, before the server cuts what is still open. */
        val MARGIN: Duration = 2.seconds

        /**
         * The bound of [environment]'s node: its `wow.wait.max-timeout` and [MARGIN], or the timeout per shutdown phase
         * the application sets when that is shorter.
         */
        fun of(environment: Environment): GracefulShutdownBound {
            val wait = WowProperties.ofOrNull(environment)?.wait?.maxTimeout?.toKotlinDuration()
                ?: WaitOptions.DEFAULT_MAX_TIMEOUT
            val phase = ShutdownTimeoutEnvironmentPostProcessor.configured(environment)?.toKotlinDuration()
            return GracefulShutdownBound(minOf(wait + MARGIN, phase ?: Duration.INFINITE))
        }
    }
}

/**
 * Ends every open query stream of the public port with `end` `{"reason":"node-stopping","resumable":true}`, and refuses
 * new ones, as the node starts to stop: its clients resume them on another node.
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
