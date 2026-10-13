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
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

val json: JsonMapper = JsonMapper.builder().build()

/**
 * The arguments of a service on [postgres]'s [database] and on [kafka], with the `postgres` and `kafka` profiles of
 * its `application.yaml`, a log of [partitions] and short cluster timings.
 */
fun backendArguments(
    postgres: PostgreSQLContainer,
    kafka: ConfluentKafkaContainer,
    database: String,
    partitions: Int,
): List<String> = listOf(
    "--spring.profiles.active=postgres,kafka",
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
 * interface. Its output goes to a log file, printed when it fails to start.
 */
class EshopService private constructor(
    private val context: String,
    private val process: Process,
    private val log: File,
    val publicPort: Int,
) : AutoCloseable {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun get(path: String): Answer = send(HttpRequest.newBuilder(uri(path)).GET())

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
        check(manifest.status == OK) { "GET /wow/manifest of $context answered ${manifest.status}: ${manifest.body}" }
        return json.readTree(manifest.body)["contexts"].values().map { it["name"].asString() }
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$publicPort$path")

    private fun send(request: HttpRequest.Builder): Answer {
        val response = http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    private fun awaitReady() {
        val deadline = System.nanoTime() + START_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            check(process.isAlive) { "The $context service exited with ${process.exitValue()}:\n${log.readText()}" }
            val ready = try {
                get("/wow/manifest").status == OK
            } catch (_: IOException) {
                false
            }
            if (ready) return
            Thread.sleep(POLL_INTERVAL.toMillis())
        }
        error("The $context service did not start in $START_TIMEOUT:\n${log.readText()}")
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

        /**
         * Starts the service of [context] by its [mainClass], with the further command-line [arguments], and waits
         * until it serves its Manifest.
         */
        fun start(context: String, mainClass: String, arguments: List<String> = emptyList()): EshopService {
            val property = "eshop.$context.classpath"
            val classpath = requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            val java = ProcessHandle.current().info().command().orElseThrow()
            val publicPort = freePort()
            val log = File.createTempFile("eshop-$context-", ".log").apply { deleteOnExit() }
            val process = ProcessBuilder(
                java,
                "-cp",
                classpath,
                mainClass,
                "--spring.main.banner-mode=off",
                "--server.address=127.0.0.1",
                "--server.port=$publicPort",
                "--wow.internal.host=127.0.0.1",
                // The node learns its internal port once bound; only the public one must be known up front.
                "--wow.internal.port=0",
                *arguments.toTypedArray(),
            ).redirectErrorStream(true).redirectOutput(log).start()
            val service = EshopService(context, process, log, publicPort)
            try {
                service.awaitReady()
            } catch (failure: Throwable) {
                service.close()
                throw failure
            }
            return service
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
