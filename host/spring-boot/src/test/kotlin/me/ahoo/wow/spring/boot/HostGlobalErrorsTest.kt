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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.problem.ProblemMapper
import me.ahoo.wow.api.problem.RetryAdvice
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

/**
 * The application's own routes answer what fails on them as the framework routes' problem+json (review M-3, decision
 * D259), unless `wow.http.global-errors.enabled` is off.
 */
class HostGlobalErrorsTest {
    @Test
    fun `an application route's failure should be a problem by default`() {
        startHost(sources = listOf(HostTestApp::class.java, AppRoutes::class.java)).use { host ->
            val down = host.client.get().uri("/app/down").exchange().expectBody(String::class.java).returnResult()
            down.status.value().assert().isEqualTo(503)
            down.responseHeaders.contentType.toString().assert().isEqualTo("application/problem+json")
            down.responseBody.assert().contains("wow.storage.unavailable")
            down.responseHeaders.getFirst("Retry-After").assert().isEqualTo("1")
        }
    }

    @Test
    fun `turned off, an application route's failure should be Spring's`() {
        startHost(
            "wow.http.global-errors.enabled" to false,
            sources = listOf(HostTestApp::class.java, AppRoutes::class.java),
        ).use { host ->
            val down = host.client.get().uri("/app/down").exchange().expectBody(String::class.java).returnResult()
            down.status.value().assert().isEqualTo(500)
            down.responseBody.orEmpty().assert().doesNotContain("wow.storage.unavailable")
        }
    }

    @Test
    fun `the application's problem mappers answer its own failures in their order, on its routes and on Wow's`() {
        startHost(sources = mapping).use { host ->
            // The mapper ordered first answers the failure it knows, though registered last.
            host.problem("/app/catalog").assert().contains("\"code\":\"bank.catalog.unreachable\"")
            // The other one answers what the first does not know.
            host.problem("/app/broken").assert().contains("\"code\":\"bank.claimed\"")
            // A store that is down is the framework's, whatever the mappers.
            host.problem("/app/down").assert().contains("wow.storage.unavailable")
        }
    }

    @Test
    fun `turned off, the application's own routes are Spring's, and Wow's routes still answer by the mappers`() {
        startHost(
            "wow.http.global-errors.enabled" to false,
            "wow.http.manifest.enabled" to true,
            sources = mapping,
        ).use { host ->
            host.problem("/app/catalog").assert().doesNotContain("bank.catalog.unreachable")
            val manifest = host.client.get().uri("/wow/manifest").header(CATALOG, "down").exchange()
                .expectBody(String::class.java).returnResult()
            manifest.status.value().assert().isEqualTo(502)
            manifest.responseBody.assert().contains("\"code\":\"bank.catalog.unreachable\"")
        }
    }

    @Test
    fun `turned off, the application's routes answer an unreadable request as Spring does`() {
        startHost(
            "wow.http.global-errors.enabled" to false,
            sources = listOf(HostTestApp::class.java, ProblemRoutesApp::class.java),
        ).use { host ->
            val result = host.client.get().uri("/app/count").exchange().expectBody(String::class.java).returnResult()
            result.status.value().assert().isEqualTo(400)
            result.responseHeaders.contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
            result.responseBody.orEmpty().assert().doesNotContain(FrameworkCodes.REQUEST_MALFORMED)
        }
    }

    private val mapping = listOf(HostTestApp::class.java, AppRoutes::class.java, AppMappers::class.java)

    private fun Host.problem(path: String): String =
        client.get().uri(path).exchange().expectBody(String::class.java).returnResult().responseBody.orEmpty()
}

private const val CATALOG = "X-Catalog"

/** An application route beside Wow's, which fails. */
@Configuration(proxyBeanMethods = false)
class AppRoutes {
    @Bean
    fun failingAppRoutes(): RouterFunction<ServerResponse> = coRouter {
        GET("/app/down") { throw StorageUnavailableException("The application's store is down.") }
        GET("/app/catalog") { throw UnsupportedOperationException("The catalog did not answer.") }
        GET("/app/broken") { throw IllegalStateException("A defect.") }
    }
}

/**
 * The application's mappers, the catch-all one registered first but ordered last; and a caller resolver that calls the
 * catalog when the request says so.
 */
@Configuration(proxyBeanMethods = false)
class AppMappers {
    @Bean
    @Order(1)
    fun claimingMapper(): ProblemMapper =
        ProblemMapper { WowProblemException("bank.claimed", 418, RetryAdvice.NONE, null, "Claimed.") }

    @Bean
    @Order(0)
    fun catalogMapper(): ProblemMapper = ProblemMapper { failure ->
        (failure as? UnsupportedOperationException)?.let {
            WowProblemException("bank.catalog.unreachable", 502, RetryAdvice.SAME_REQUEST, null, "No catalog.")
        }
    }

    @Bean
    fun catalogCallers(): CallerResolver = CallerResolver { request ->
        if (request.headers().firstHeader(CATALOG) != null) {
            throw UnsupportedOperationException("The catalog did not answer.")
        }
        Caller.ANONYMOUS
    }
}
