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
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

/**
 * The application's own routes answer what fails on them as the framework routes' problem+json (review M-3, decision
 * D259), unless `wow.http.global-errors` is off.
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
            "wow.http.global-errors" to false,
            sources = listOf(HostTestApp::class.java, AppRoutes::class.java),
        ).use { host ->
            val down = host.client.get().uri("/app/down").exchange().expectBody(String::class.java).returnResult()
            down.status.value().assert().isEqualTo(500)
            down.responseBody.orEmpty().assert().doesNotContain("wow.storage.unavailable")
        }
    }
}

/** An application route beside Wow's, which fails. */
@Configuration(proxyBeanMethods = false)
class AppRoutes {
    @Bean
    fun failingAppRoutes(): RouterFunction<ServerResponse> = coRouter {
        GET("/app/down") { throw StorageUnavailableException("The application's store is down.") }
    }
}
