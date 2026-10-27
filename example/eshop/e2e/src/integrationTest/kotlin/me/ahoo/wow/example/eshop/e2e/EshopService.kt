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

package me.ahoo.wow.example.eshop.e2e

import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

val json: JsonMapper = JsonMapper.builder().build()

/**
 * The arguments of a service on [postgres]'s [database] and on [kafka], with the `postgres` and `kafka` profiles of
 * its `application.yaml` (and any further [profiles]), a log of [partitions] and short cluster timings.
 */
fun backendArguments(
    postgres: PostgreSQLContainer,
    kafka: ConfluentKafkaContainer,
    database: String,
    partitions: Int,
    // A development profile by default: the requests name their callers in the Wow-* headers.
    profiles: List<String> = listOf("dev"),
): List<String> = listOf(
    "--spring.profiles.active=" + (listOf("postgres", "kafka") + profiles).joinToString(","),
    "--spring.datasource.url=jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database",
    "--spring.datasource.username=${postgres.username}",
    "--spring.datasource.password=${postgres.password}",
    "--wow.kafka.bootstrap-servers=${kafka.bootstrapServers}",
    "--wow.log.partitions=$partitions",
    "--wow.cluster.lease-ttl=5s",
    "--wow.cluster.renew-interval=1s",
    "--wow.cluster.member-ttl=5s",
    "--wow.cluster.heartbeat-interval=1s",
    "--wow.cluster.reconcile-interval=200ms",
    "--wow.cluster.route-refresh-interval=200ms",
    "--wow.subscription.checkpoint-interval=100ms",
)

/** A response: its status and its body. */
data class Answer(val status: Int, val body: String)

/**
 * One eShop service in a JVM of its own, started from the runtime class path of its server project (the system
 * property `eshop.<context>.classpath`, which the build passes), with its public and internal ports on the loopback
 * interface, each at a port the system chooses unless given: the harness reads the ports from the service's log once
 * they are bound. Its output goes to a log file, printed when it fails to start.
 */
class EshopService private constructor(
    val name: String,
    private val process: Process,
    private val log: File,
    val publicPort: Int,
    /** The internal port, where the other services call it synchronously (decision D193). */
    val internalPort: Int,
) : AutoCloseable {
    /** The service's process id, for its resource samples. */
    val pid: Long get() = process.pid()

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun get(path: String, headers: Map<String, String> = emptyMap()): Answer = send(
        HttpRequest.newBuilder(uri(path)).GET().also { request -> headers.forEach { (name, value) -> request.header(name, value) } },
    )

    fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): Answer = send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .also { request -> headers.forEach { (name, value) -> request.header(name, value) } },
    )

    /** The service's output so far. */
    fun output(): String = log.readText()

    /** The names of the contexts the service's Manifest lists. */
    fun contexts(): List<String> {
        val manifest = get("/wow/manifest")
        check(manifest.status == OK) { "GET /wow/manifest of $name answered ${manifest.status}: ${manifest.body}" }
        return json.readTree(manifest.body)["contexts"].values().map { it["name"].asString() }
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$publicPort$path")

    private fun send(request: HttpRequest.Builder): Answer {
        val response = http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    private fun awaitReady(headers: Map<String, String>) {
        val deadline = System.nanoTime() + START_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            check(process.isAlive) { "The $name service exited with ${process.exitValue()}:\n${log.readText()}" }
            val ready = try {
                get("/wow/manifest", headers).status == OK
            } catch (_: IOException) {
                false
            }
            if (ready) return
            Thread.sleep(POLL_INTERVAL.toMillis())
        }
        error("The $name service did not start in $START_TIMEOUT:\n${log.readText()}")
    }

    /** Kills the service's JVM at once (`SIGKILL`), as a crash would: it neither leaves its cluster nor gives up a lease. */
    fun kill() {
        process.destroyForcibly().waitFor()
        http.shutdownNow()
    }

    override fun close() {
        process.destroy()
        if (!process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
        }
        http.shutdownNow()
    }

    companion object {
        private const val OK = 200
        private val START_TIMEOUT: Duration = Duration.ofSeconds(90)
        private val STOP_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val POLL_INTERVAL: Duration = Duration.ofMillis(200)

        private const val BIND_ATTEMPTS = 5

        /** The loggers of Spring Boot's Reactor Netty web server and of Wow's internal server, and what they log. */
        private const val WEB_SERVER_LOGGER = "org.springframework.boot.reactor.netty.NettyWebServer"
        private const val INTERNAL_SERVER_LOGGER = "me.ahoo.wow.http.internal.InternalPort"
        private val PUBLIC_BOUND = Regex("""Netty started on port (\d+) \(http\)""")
        private val INTERNAL_BOUND = Regex("""Wow internal port bound at \S*:(\d+)\.""")
        private val PORT_TAKEN = Regex("""(?i)already in use""")

        /**
         * Starts the service of [context] by its [mainClass], with the further command-line [arguments] and the JVM's
         * own [jvmArguments] (a heap bound, say), and waits until it serves its Manifest, asked with [readyHeaders] (the
         * credentials of a service that answers no anonymous request). Its [publicPort] and [internalPort] are ones the
         * system chooses by default, so no other process can take a port picked before the service binds it; a
         * replacement takes the ports of the node it replaces, the address the other services call it at (decision
         * D193), and is started again, at most [BIND_ATTEMPTS] times, while another process holds one.
         */
        fun start(
            context: String,
            mainClass: String,
            arguments: List<String> = emptyList(),
            jvmArguments: List<String> = emptyList(),
            publicPort: Int = 0,
            internalPort: Int = 0,
            readyHeaders: Map<String, String> = emptyMap(),
        ): EshopService {
            var attempt = 1
            while (true) {
                try {
                    return startOnce(context, mainClass, arguments, jvmArguments, publicPort, internalPort, readyHeaders)
                } catch (taken: PortTakenException) {
                    if (attempt == BIND_ATTEMPTS) throw taken
                    attempt++
                }
            }
        }

        private fun startOnce(
            context: String,
            mainClass: String,
            arguments: List<String>,
            jvmArguments: List<String>,
            publicPort: Int,
            internalPort: Int,
            readyHeaders: Map<String, String>,
        ): EshopService {
            val property = "eshop.$context.classpath"
            val server = requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            // The soak adds its own classes (SoakSignals, an auto-configuration) to every node's class path.
            val classpath = System.getProperty("eshop.extra-classpath")?.let { server + File.pathSeparator + it } ?: server
            val java = ProcessHandle.current().info().command().orElseThrow()
            // Kept in `eshop.logs` (the build's `eshop-logs`) when the build names it, so a failed run can be read.
            val logs = System.getProperty("eshop.logs")?.let { File(it).apply { mkdirs() } }
            val log = File.createTempFile("eshop-$context-", ".log", logs).apply { if (logs == null) deleteOnExit() }
            val process = ProcessBuilder(
                java,
                *jvmArguments.toTypedArray(),
                "-cp",
                classpath,
                mainClass,
                "--spring.main.banner-mode=off",
                "--server.address=127.0.0.1",
                "--server.port=$publicPort",
                "--logging.level.$WEB_SERVER_LOGGER=info",
                "--logging.level.$INTERNAL_SERVER_LOGGER=info",
                "--wow.internal.host=127.0.0.1",
                "--wow.internal.port=$internalPort",
                *arguments.toTypedArray(),
            ).redirectErrorStream(true).redirectOutput(log).start()
            val service = try {
                val bound = awaitPorts(context, process, log)
                EshopService(context, process, log, bound.first, bound.second)
            } catch (failure: Throwable) {
                process.destroyForcibly().waitFor()
                throw failure
            }
            try {
                service.awaitReady(readyHeaders)
            } catch (failure: Throwable) {
                service.close()
                throw failure
            }
            return service
        }

        /** The public and internal ports the service of [context] logged once it bound them. */
        private fun awaitPorts(context: String, process: Process, log: File): Pair<Int, Int> {
            val deadline = System.nanoTime() + START_TIMEOUT.toNanos()
            while (System.nanoTime() < deadline) {
                val output = log.readText()
                val public = PUBLIC_BOUND.find(output)?.groupValues?.get(1)?.toInt()
                val internal = INTERNAL_BOUND.find(output)?.groupValues?.get(1)?.toInt()
                if (public != null && internal != null) {
                    return public to internal
                }
                if (!process.isAlive) {
                    val message = "The $context service exited with ${process.exitValue()}:\n$output"
                    if (PORT_TAKEN.containsMatchIn(output)) throw PortTakenException(message)
                    error(message)
                }
                Thread.sleep(POLL_INTERVAL.toMillis())
            }
            error("The $context service did not bind its ports in $START_TIMEOUT:\n${log.readText()}")
        }
    }

    /** A service that could not start because another process held a port it was given. */
    private class PortTakenException(message: String) : IllegalStateException(message)
}
