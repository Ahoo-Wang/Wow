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
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spring.boot.node.GracefulShutdownBound
import me.ahoo.wow.spring.boot.node.ServiceNodeListener
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The host starts and stops the node in the order of decision D108 and reports its readiness around it. */
class HostLifecycleTest {
    @Test
    fun `readiness and the stop steps should follow the start and stop order`() {
        val host = startHost(sources = listOf(HostTestApp::class.java, TimelineConfiguration::class.java))
        val timeline = host.context.getBean(Timeline::class.java)
        timeline.host = host
        val node = host.node
        host.post("/bank/account/open-account", """{"owner":"carol"}""").status.assert().isEqualTo(200)
        timeline.events.assert().containsExactly("readiness ACCEPTING_TRAFFIC")

        host.close()

        val steps = ServiceNode.Step.entries.map { "$it public=false internal=true" }
        timeline.events.assert().containsExactly(
            "readiness ACCEPTING_TRAFFIC",
            "readiness REFUSING_TRAFFIC",
            *steps.toTypedArray(),
        )
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
        host.internalPortOpen().assert().isFalse()
        host.publicPortOpen().assert().isFalse()
    }

    /** When the node's stop began: its first step, in `System.nanoTime()`. */
    class StopStart {
        val at = AtomicLong(0)
    }

    @Configuration(proxyBeanMethods = false)
    class StopRecording {
        @Bean
        fun stopStart(): StopStart = StopStart()

        @Bean
        fun stopStartRecorder(start: StopStart): ServiceNodeListener = ServiceNodeListener {
            start.at.compareAndSet(0, System.nanoTime())
        }
    }

    @Test
    fun `a request whose body stalls should hold the public server's shutdown no longer than the wait bound (#6958)`() {
        // operate/topology.md: the public server's graceful shutdown lasts as long as its longest request, up to
        // wow.wait.max-timeout and GracefulShutdownBound.MARGIN; past it the server's stop cuts what is still open,
        // within Reactor Netty's dispose (3 s). Neither port sets a read timeout: the body never comes.
        val host = startHost(
            "wow.wait.max-timeout" to "3s",
            "wow.wait.default-timeout" to "3s",
            sources = listOf(HostTestApp::class.java, StopRecording::class.java),
        )
        val stopStart = host.context.getBean(StopStart::class.java)
        Socket().use { stalled ->
            stalled.connect(InetSocketAddress("127.0.0.1", host.publicPort), 1_000)
            stalled.getOutputStream().apply {
                write(
                    (
                        "POST /bank/account/open-account HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                            "Content-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"owner\":\""
                        ).toByteArray(),
                )
                flush()
            }
            Thread.sleep(300)
            val closing = System.nanoTime()
            CompletableFuture.runAsync { host.close() }.get(60, TimeUnit.SECONDS)
            val held = TimeUnit.NANOSECONDS.toMillis(stopStart.at.get() - closing)
            // The wait bound and its margin, and room for the readiness change before them.
            held.assert().describedAs("the node began to stop ${held}ms after the close")
                .isLessThan(3_000 + GracefulShutdownBound.MARGIN.inWholeMilliseconds + 1_500)
            // What was still open is closed: the stalled request's connection reads its end, or is reset.
            stalled.soTimeout = 1_000
            val end = runCatching { stalled.getInputStream().read() }
            (end.getOrNull() == -1 || end.exceptionOrNull() is SocketException).assert()
                .describedAs("the stalled connection after the close: $end").isTrue()
        }
    }
}
