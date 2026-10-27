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

package me.ahoo.wow.ops.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.ops.api.parked.ParkedStatus
import me.ahoo.wow.ops.testing.LedgerSimulatorRoutes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The ops console (roadmap 7.1b) as the server serves it: its build's static files from
 * `spring.web.resources.static-locations`, beside the ops context's routes, and the ledger simulator its end-to-end
 * test publishes entries through ([LedgerSimulatorRoutes]).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpsConsoleTest {
    /** A console of one page and one script, where the distribution's `ui/` would be. */
    private val console: Path = Files.createTempDirectory("ops-console").also {
        it.resolve("index.html").writeText("<!doctype html><title>$MARKER</title>")
        it.resolve("assets").createDirectories().resolve("index.js").writeText("console.log('$MARKER');")
    }

    private val ops: OpsNode = startOps(
        "spring.web.resources.static-locations" to console.toUri().toString(),
        "wow.query.memory.enabled" to true,
        sources = listOf(LedgerSimulatorRoutes::class.java),
    )

    private val client: WebTestClient = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:${ops.context.environment.getRequiredProperty("local.server.port")}")
        .responseTimeout(Duration.ofSeconds(30))
        .build()

    @AfterAll
    fun close() {
        ops.close()
        console.toFile().deleteRecursively()
    }

    @Test
    fun `the server should serve the console's files to anyone, and its routes to callers alone`() {
        client.get().uri("/").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).value { it.assert().contains(MARKER) }
        client.get().uri("/assets/index.js").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).value { it.assert().contains(MARKER) }
        // The console's files are public; what it reads is not.
        ops.post(OpsContract.QUERY, """{"kind":"list"}""", Who.NOBODY).status.assert().isEqualTo(UNAUTHORIZED)
    }

    @Test
    fun `the configuration should serve the distribution's ui folder`() {
        val yaml = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).first()
        yaml.getProperty("spring.web.resources.static-locations").toString().assert().contains("file:./ui/")
    }

    @Test
    fun `the ledger simulator should publish an entry and its resolution, and read the commands sent back`() {
        val context = "console-${System.nanoTime()}"
        val aggregate = """{"context":"$context","name":"payment","tenantId":"(0)","id":"p-1"}"""
        val entry = """{"service":"$context-service","context":"$context","subscription":"${OpsContract.SAGA}",
            "aggregate":$aggregate,"fromVersion":2,"reason":"The gateway is unreachable.",
            "failedAt":${OpsContract.FAILED_AT},"retries":3,
            "retrySpec":{"maxRetries":3,"initialBackoff":"PT1M","executionTimeout":"PT2M"},
            "status":"${ParkedStatus.EXHAUSTED}","recordedAt":${OpsContract.FAILED_AT + 1}}"""
        val id = simulate("parked", entry)
        eventually("$id recorded") { ops.get("/ops/parked/$id/state", Who.OPERATOR).status == OK }

        ops.post("/ops/parked/$id/resume", "{}", Who.OPERATOR).assertOk()
        eventually("the resume sent to $context") {
            client.get().uri("${LedgerSimulatorRoutes.PATH}/commands/$context").exchange()
                .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
                .contains("wow.ledger.Resume")
        }

        val resolution = """{"service":"$context-service","context":"$context","subscription":"${OpsContract.SAGA}",
            "aggregate":$aggregate,"failedAt":${OpsContract.FAILED_AT},"outcome":"RESUMED",
            "resolvedAt":${OpsContract.FAILED_AT + OpsContract.HOUR}}"""
        simulate("resolved", resolution).assert().isEqualTo(id)
        eventually("$id resolved") {
            ops.get("/ops/parked/$id/state", Who.OPERATOR).json["state"]["status"].asString() == ParkedStatus.RESOLVED
        }
    }

    private fun simulate(what: String, body: String): String =
        client.post().uri("${LedgerSimulatorRoutes.PATH}/$what")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
            .let { json.readTree(it)["id"].asString() }

    private companion object {
        const val MARKER = "ops-console-under-test"
        const val OK = 200
        const val UNAUTHORIZED = 401
    }
}
