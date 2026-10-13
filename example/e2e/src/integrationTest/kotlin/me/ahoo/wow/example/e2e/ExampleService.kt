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

package me.ahoo.wow.example.e2e

import tools.jackson.databind.JsonNode
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

/** A response: its status and its body as JSON (an empty object when it has none). */
data class Answer(val status: Int, val json: JsonNode)

/**
 * One service of the example in a JVM of its own, started from the runtime class path of its server project (the
 * system property `example.<name>.classpath`, which the build passes) with [arguments] over its `application.yaml`,
 * its public and internal ports on the loopback interface. Its output goes to [log].
 *
 * [restart] stops the JVM at once, as a crash would (no graceful hand-off, no last checkpoint), and starts it again
 * with the same arguments and public port; [kill] and [relaunch] do the same in two steps. [pause] and [resume] stop
 * and continue the JVM (`SIGSTOP`, `SIGCONT`): a node that hangs, holding its connections.
 */
class ExampleService private constructor(
    private val name: String,
    private val mainClass: String,
    private val arguments: List<String>,
    val log: File,
) : AutoCloseable {
    private val publicPort: Int = freePort()
    private var process: Process = launch()
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun get(path: String): Answer = send(HttpRequest.newBuilder(uri(path)).GET())

    fun post(path: String, body: String = "{}", headers: Map<String, String> = emptyMap()): Answer = send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (header, value) -> header(header, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body)),
    )

    /**
     * Posts [body] to [path] asking for `text/event-stream`, and hands each event's data, as JSON, to [onEvent] as it
     * arrives, until the stream ends; throws when the connection breaks first.
     */
    fun stream(path: String, body: String, headers: Map<String, String>, onEvent: (JsonNode) -> Unit) {
        val request = HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { headers.forEach { (header, value) -> header(header, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
        check(response.statusCode() == OK) { "The event stream answered ${response.statusCode()}." }
        response.body().use { lines ->
            lines.forEach { line ->
                if (line.startsWith(DATA)) onEvent(json.readTree(line.removePrefix(DATA).trim()))
            }
        }
    }

    /** The state of the aggregate [aggregate] [id] of [context], or null when it has none yet. */
    fun state(context: String, aggregate: String, id: String): Answer? =
        get("/$context/$aggregate/$id/state").takeIf { it.status == OK }

    /** The last [lines] lines of the service's output. */
    fun tail(lines: Int = TAIL_LINES): String = log.readLines().takeLast(lines).joinToString("\n")

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$publicPort$path")

    private fun send(request: HttpRequest.Builder): Answer {
        val response = http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString())
        val body = response.body().takeIf { it.isNotBlank() }?.let { json.readTree(it) } ?: json.createObjectNode()
        return Answer(response.statusCode(), body)
    }

    private fun launch(): Process {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val property = "example.$name.classpath"
        val classpath = requireNotNull(System.getProperty(property)) { "System property $property is not set." }
        return ProcessBuilder(
            listOf(java, "-cp", classpath, mainClass) + arguments + listOf(
                "--spring.main.banner-mode=off",
                "--server.address=127.0.0.1",
                "--server.port=$publicPort",
                "--wow.internal.host=127.0.0.1",
                // The node learns its internal port once bound; only the public one must be known up front.
                "--wow.internal.port=0",
            ),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
    }

    private fun awaitReady() {
        val deadline = System.nanoTime() + START_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            check(process.isAlive) { "The $name service exited with ${process.exitValue()}:\n${tail()}" }
            val ready = try {
                get("/wow/manifest").status == OK
            } catch (_: IOException) {
                false
            }
            if (ready) return
            Thread.sleep(POLL_INTERVAL.toMillis())
        }
        error("The $name service did not start in $START_TIMEOUT:\n${tail()}")
    }

    /** Kills the service's JVM and starts it again, waiting until it serves its Manifest. */
    fun restart() {
        kill()
        relaunch()
    }

    /** Kills the service's JVM at once, as a crash would. */
    fun kill() {
        process.destroyForcibly().waitFor()
    }

    /** Starts the service's JVM again after [kill], waiting until it serves its Manifest. */
    fun relaunch() {
        check(!process.isAlive) { "The $name service is running." }
        process = launch()
        awaitReady()
    }

    /** Stops the service's JVM without ending it: it holds its connections and does nothing until [resume]. */
    fun pause() = signal("STOP")

    /** Continues the service's JVM after [pause]. */
    fun resume() = signal("CONT")

    private fun signal(name: String) {
        val kill = ProcessBuilder("kill", "-$name", process.pid().toString()).inheritIO().start()
        check(kill.waitFor() == 0) { "kill -$name ${process.pid()} failed." }
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
        private const val DATA = "data:"
        private const val TAIL_LINES = 80
        private val START_TIMEOUT: Duration = Duration.ofSeconds(120)
        private val STOP_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val POLL_INTERVAL: Duration = Duration.ofMillis(200)

        /** Starts the service [name] by its [mainClass] with [arguments] and waits until it serves its Manifest. */
        fun start(name: String, mainClass: String, arguments: List<String>): ExampleService {
            val log = File.createTempFile("example-$name-", ".log").apply { deleteOnExit() }
            val service = ExampleService(name, mainClass, arguments, log)
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
