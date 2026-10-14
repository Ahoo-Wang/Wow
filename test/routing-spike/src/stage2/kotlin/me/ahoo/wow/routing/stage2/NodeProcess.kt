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

package me.ahoo.wow.routing.stage2

import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** One node of stage 2: an [H2Node] in a JVM of its own, on the driver's class path. */
class NodeProcess private constructor(
    val id: String,
    val publicPort: Int,
    private val process: Process,
    val log: File,
) : AutoCloseable {
    val alive: Boolean get() = process.isAlive

    /** The CPU time the node's JVM has used so far; zero where the platform does not tell. */
    fun cpu(): Duration = process.toHandle().info().totalCpuDuration().orElse(Duration.ZERO)

    /** Kills the JVM at once (`SIGKILL`), as a crash would: it neither leaves the cluster nor gives up a lease. */
    fun kill() {
        process.destroyForcibly().waitFor()
    }

    override fun close() {
        process.destroy()
        if (!process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
        }
    }

    private fun awaitReady(http: HttpClient) {
        val deadline = System.nanoTime() + START_TIMEOUT.toNanos()
        val manifest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$publicPort/wow/manifest"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        while (System.nanoTime() < deadline) {
            check(
                process.isAlive
            ) { "Node $id exited with ${process.exitValue()}:\n${log.readText().takeLast(LOG_TAIL)}" }
            val ready = try {
                http.send(manifest, HttpResponse.BodyHandlers.discarding()).statusCode() == OK
            } catch (_: IOException) {
                false
            }
            if (ready) return
            Thread.sleep(POLL.toMillis())
        }
        error("Node $id did not start in $START_TIMEOUT:\n${log.readText().takeLast(LOG_TAIL)}")
    }

    companion object {
        private const val OK = 200

        /** The file class of [main] in H2Node.kt. */
        private const val MAIN_CLASS = "me.ahoo.wow.routing.stage2.H2NodeKt"
        private const val LOG_TAIL = 8_000
        private val START_TIMEOUT: Duration = Duration.ofSeconds(120)
        private val STOP_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val POLL: Duration = Duration.ofMillis(200)

        /**
         * Starts the nodes [ids] at once on [postgres] with [router], and waits until each serves its Manifest; each
         * one's output goes to `<run>-<id>.log` in [logs]. The cluster settings are the host's defaults (lease TTL 10 s,
         * forward timeout 1 s): what a deployment gets.
         */
        fun startAll(
            ids: List<String>,
            settings: Stage2Settings,
            postgres: PostgreSQLContainer,
            router: Router,
            logs: File,
            run: String,
        ): List<NodeProcess> {
            val java = ProcessHandle.current().info().command().orElseThrow()
            val nodes = ids.map { id ->
                val publicPort = freePort()
                val log = File(logs, "$run-$id.log")
                val process = ProcessBuilder(
                    java,
                    "-Xms${settings.nodeHeap}",
                    "-Xmx${settings.nodeHeap}",
                    "-cp",
                    settings.classpath,
                    MAIN_CLASS,
                    "--spring.main.banner-mode=off",
                    "--spring.application.name=$H2_SERVICE",
                    "--server.address=127.0.0.1",
                    "--server.port=$publicPort",
                    "--wow.service=$H2_SERVICE",
                    "--wow.node.id=$id",
                    "--wow.internal.host=127.0.0.1",
                    // The node learns its internal port once bound and advertises it to the others.
                    "--wow.internal.port=0",
                    "--wow.backend=postgres",
                    "--wow.cluster.router=${router.property}",
                    "--wow.log.partitions=${settings.partitions}",
                    "--h2.datasource.url=${postgres.jdbcUrl}",
                    "--h2.datasource.username=${postgres.username}",
                    "--h2.datasource.password=${postgres.password}",
                    "--h2.datasource.pool=${settings.nodePool}",
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(log)).start()
                NodeProcess(id, publicPort, process, log)
            }
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            try {
                nodes.forEach { it.awaitReady(http) }
            } catch (failure: Throwable) {
                nodes.forEach { it.close() }
                throw failure
            } finally {
                http.close()
            }
            return nodes
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
