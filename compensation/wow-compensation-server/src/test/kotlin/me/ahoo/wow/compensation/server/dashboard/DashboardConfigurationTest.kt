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

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.web.WebProperties
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.file.Files
import java.nio.file.Path

class DashboardConfigurationTest {
    @TempDir
    lateinit var uiDirectory: Path

    @ParameterizedTest
    @ValueSource(strings = ["/", "/to-retry", "/active", "/active?cluster=test"])
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
}
