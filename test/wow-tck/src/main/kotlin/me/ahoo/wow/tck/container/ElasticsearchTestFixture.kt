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

package me.ahoo.wow.tck.container

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext

class ElasticsearchTestFixture(
    private val indexPrefix: String = "wow_it",
) : BeforeEachCallback, TestWatcher {
    val password: String
        get() = WowTestContainers.elasticPassword

    val hostAddress: String
        get() = WowTestContainers.elasticsearch.httpHostAddress

    val sslContext: SSLContext
        get() = WowTestContainers.elasticsearch.createSslContextFromCa()

    override fun beforeEach(context: ExtensionContext) {
        waitUntilAuthenticated()
    }

    fun index(name: String): String = "${ContainerTestIds.nextName(indexPrefix)}_$name"

    fun waitUntilAuthenticated() {
        val basicAuth = Base64.getEncoder().encodeToString(
            "${WowTestContainers.ELASTIC_USER}:$password".toByteArray(StandardCharsets.UTF_8),
        )
        val httpClient = HttpClient.newBuilder()
            .sslContext(sslContext)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://$hostAddress/_security/_authenticate"))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Basic $basicAuth")
            .GET()
            .build()

        ElasticsearchAuthenticationWaiter.waitUntilAuthenticated(
            authenticate = {
                httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            },
        )
    }

    override fun testFailed(context: ExtensionContext, cause: Throwable?) {
        ContainerDiagnostics.printFailure("elasticsearch", WowTestContainers.elasticsearch, cause)
    }
}
