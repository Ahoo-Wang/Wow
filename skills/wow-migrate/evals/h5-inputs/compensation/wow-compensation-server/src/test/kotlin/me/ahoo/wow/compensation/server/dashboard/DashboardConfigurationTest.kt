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

package me.ahoo.wow.compensation.server.dashboard

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.web.WebProperties
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class DashboardConfigurationTest {
    @TempDir
    lateinit var uiDirectory: Path

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/",
            "/executions",
            "/executions?view=system:execution-failed:to-retry&start=1&end=2",
            "/executions?view=system:execution-failed:active&cluster=%7B%7D",
            "/executions?view=system:execution-failed:unrecoverable&id=EF-1",
            "/events",
            "/events?view=system:execution-history:history",
            "/boards",
            "/boards?view=system:overview:home",
        ]
    )
    fun servesDashboardEntryPoint(path: String) {
        val html = "<!doctype html><html><body>compensation-dashboard</body></html>"
        Files.writeString(uiDirectory.resolve("index.html"), html)
        val properties = WebProperties().apply {
            resources.staticLocations = arrayOf(uiDirectory.toUri().toString())
        }
        val client = WebTestClient.bindToController(
            DashboardConfiguration(properties, DefaultResourceLoader())
        ).build()
        client.get().uri(path).exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.TEXT_HTML)
            .expectBody(String::class.java).isEqualTo(html)
    }

    @ParameterizedTest
    @CsvSource(
        "/to-retry?id=EF-1, executions?view=system%3Aexecution-failed%3Ato-retry&id=EF-1",
        "/unrecoverable?id=EF-2, executions?view=system%3Aexecution-failed%3Aunrecoverable&id=EF-2",
        "/non-retryable?id=EF-3, executions?view=system%3Aexecution-failed%3Anon-retryable&id=EF-3",
        "/succeeded?id=EF-4, executions?view=system%3Aexecution-failed%3Asucceeded&id=EF-4",
        "/active, executions?view=system%3Aexecution-failed%3Aactive",
        "/executing, executions?view=system%3Aexecution-failed%3Aexecuting",
        "/next-retry, executions?view=system%3Aexecution-failed%3Anext-retry",
        "/to-retry;jsessionid=x?id=EF-5, executions?view=system%3Aexecution-failed%3Ato-retry&id=EF-5",
    )
    fun redirectsLegacyAlertLinks(path: String, location: String) {
        val client = WebTestClient.bindToController(
            DashboardConfiguration(WebProperties(), DefaultResourceLoader())
        ).build()
        client.get().uri(path).exchange()
            .expectStatus().isFound
            .expectHeader().location(location)
    }

    @Test
    fun redirectsLegacyAlertLinksUnderTheContextPath() {
        val client = WebTestClient.bindToController(
            DashboardConfiguration(WebProperties(), DefaultResourceLoader())
        ).webFilter<WebTestClient.ControllerSpec>(
            WebFilter { exchange, chain ->
                chain.filter(exchange.mutate().request { it.contextPath("/console") }.build())
            },
        ).build()
        val location = client.get().uri("/console/to-retry?id=EF-1").exchange()
            .expectStatus().isFound
            .returnResult(Void::class.java).responseHeaders.location!!

        URI.create("http://host/console/to-retry?id=EF-1").resolve(location).toString().assert()
            .isEqualTo("http://host/console/executions?view=system%3Aexecution-failed%3Ato-retry&id=EF-1")
    }
}
