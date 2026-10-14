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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.internal.internalRoutes
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.AvailabilityState
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationEventPublisher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The host's lifecycles outside a Spring context: what each start and stop does. */
class WowLifecyclesTest {
    private val events = CopyOnWriteArrayList<Any>()
    private val publisher = ApplicationEventPublisher { events += it }

    private fun node(address: String? = "127.0.0.1:1"): ServiceNode {
        val dependencies = Dependencies.of(DecidedCommands())
        val providers = listOf(BankModels())
        return ServiceNode(
            service = "bank-service",
            node = "node-1",
            address = address,
            models = ServiceNode.models(dependencies, providers),
            manifest = ManifestLoader(javaClass.classLoader, WowJson.mapper).load("bank-service"),
            backends = HostBackends.memory(2).factory,
            channel = ClusterChannel { _, _ -> error("A single node forwards nothing.") },
            options = ServiceNodeOptions(routing = Routing.LOCAL),
        )
    }

    private class Readiness(private val state: ReadinessState) : ApplicationAvailability {
        override fun <S : AvailabilityState> getState(type: Class<S>, defaultState: S): S = defaultState

        override fun <S : AvailabilityState> getState(type: Class<S>): S? = null

        override fun <S : AvailabilityState> getLastChangeEvent(type: Class<S>): AvailabilityChangeEvent<S>? = null

        override fun getReadinessState(): ReadinessState = state

        override fun getLivenessState(): LivenessState = LivenessState.CORRECT
    }

    @Test
    fun `stopping the node without a callback should report not ready and stop it`() {
        val node = node()
        val lifecycle = ServiceNodeLifecycle(node, publisher) { Readiness(ReadinessState.ACCEPTING_TRAFFIC) }
        lifecycle.start()
        lifecycle.isRunning.assert().isTrue()
        node.state.assert().isEqualTo(ServiceNode.State.RUNNING)

        lifecycle.stop()

        lifecycle.isRunning.assert().isFalse()
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
        events.filterIsInstance<AvailabilityChangeEvent<*>>().single().state
            .assert().isEqualTo(ReadinessState.REFUSING_TRAFFIC)
    }

    @Test
    fun `a stop with a callback should not report not ready twice`() {
        val node = node()
        val lifecycle = ServiceNodeLifecycle(node, publisher) { Readiness(ReadinessState.REFUSING_TRAFFIC) }
        lifecycle.start()
        val stopped = CountDownLatch(1)

        lifecycle.stop { stopped.countDown() }

        stopped.await(30, TimeUnit.SECONDS).assert().isTrue()
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
        events.assert().isEmpty()
    }

    @Test
    fun `the internal server should bind once and unbind`() {
        val node = node()
        val server = InternalServer(0, "127.0.0.1", internalRoutes(node.forwardHandler, node.waits))
        val lifecycle = InternalServerLifecycle(server)
        lifecycle.start()
        lifecycle.start()
        lifecycle.isRunning.assert().isTrue()
        lifecycle.stop()
        lifecycle.isRunning.assert().isFalse()
        assertThrows<IllegalStateException> { server.boundPort }
    }

    @Test
    fun `a node without an address should learn the port the internal server is bound at`() {
        val node = node(address = null)
        node.hasAddress.assert().isFalse()
        val server = InternalServer(0, "127.0.0.1", internalRoutes(node.forwardHandler, node.waits))
        val lifecycle = InternalServerLifecycle(server) { port -> node.bind("127.0.0.1:$port") }
        lifecycle.start()
        try {
            server.boundPort.assert().isNotEqualTo(0)
            node.address.assert().isEqualTo("127.0.0.1:${server.boundPort}")
        } finally {
            lifecycle.stop()
        }
    }

    @Test
    fun `the backends lifecycle should create the schemas once started`() {
        var created = 0
        val backends = HostBackends(null, AppendQueueOptions(), listOf({ created++ })) { _, _ ->
            HostBackends.memory(1).factory.backends("bank")
        }
        val lifecycle = BackendsLifecycle(backends)
        lifecycle.start()
        created.assert().isEqualTo(1)
        lifecycle.isRunning.assert().isTrue()
        lifecycle.stop()
        lifecycle.isRunning.assert().isFalse()
    }
}
