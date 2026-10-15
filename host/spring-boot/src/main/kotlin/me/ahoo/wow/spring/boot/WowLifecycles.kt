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
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import kotlin.concurrent.thread

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
 *   serves the other nodes' forwards and signals until then → the backends, closed with their beans.
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

    /** [BackendsLifecycle]: first of all. */
    const val BACKENDS_PHASE: Int = INTERNAL_SERVER_PHASE - 1024

    /** [QueryIndexesLifecycle]: after the backends' schemas exist, before the internal server starts. */
    const val QUERY_INDEXES_PHASE: Int = BACKENDS_PHASE + 512
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
    private val server: InternalServer,
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
    private val availability: () -> ApplicationAvailability?,
) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        runBlocking { node.start() }
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
