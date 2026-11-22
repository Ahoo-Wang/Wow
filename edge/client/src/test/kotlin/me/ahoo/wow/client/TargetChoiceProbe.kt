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

package me.ahoo.wow.client

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.lang.management.ManagementFactory
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/**
 * What choosing a request's targets costs (#6519): the time and the bytes allocated per choice of three targets,
 * with no node bypassed and with one, on one thread after a warm-up. Not run by the build: `WOW_PROBE=true`, through
 * `test/benchmarks/harness/quiet.sh`. Compare the figures of one machine before and after, never across machines.
 */
@EnabledIfEnvironmentVariable(named = "WOW_PROBE", matches = "true")
class TargetChoiceProbe {
    private val targets = listOf(
        URI.create("http://10.0.0.1:9093"),
        URI.create("http://10.0.0.2:9093"),
        URI.create("http://10.0.0.3:9093"),
    )

    private fun transport(silent: SilentPeers = SilentPeers()) = HttpTransport(
        object : EndpointResolver {
            override suspend fun resolve(context: String, port: Port): URI? = targets.first()

            override suspend fun resolveAll(context: String, port: Port): List<URI> = targets
        },
        ClientMode.Service("basket-service"),
        HttpCommandCaller.client(),
        30.seconds,
        RetryPolicy.NONE,
        silent = silent,
    )

    private fun measure(name: String, transport: HttpTransport) = runBlocking {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        var sink = 0
        repeat(WARM_UP) { sink += transport.targetsOf("mail").size }
        val bytes = threads.currentThreadAllocatedBytes
        val started = System.nanoTime()
        repeat(CHOICES) { sink += transport.targetsOf("mail").size }
        val nanos = System.nanoTime() - started
        val allocated = threads.currentThreadAllocatedBytes - bytes
        println(
            "PROBE $name: ${"%.1f".format(nanos.toDouble() / CHOICES)} ns/choice, " +
                "${"%.1f".format(allocated.toDouble() / CHOICES)} B/choice ($sink)",
        )
    }

    @Test
    fun `choosing the targets`() {
        measure("none-bypassed", transport())
        val silent = SilentPeers()
        silent.silenced("mail", Port.INTERNAL, "http://10.0.0.2:9093")
        measure("one-bypassed", transport(silent))
    }

    private companion object {
        const val WARM_UP = 2_000_000
        const val CHOICES = 5_000_000
    }
}
