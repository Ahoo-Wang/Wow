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

import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import reactor.core.publisher.Mono
import java.net.URI

@Controller
class DashboardConfiguration {
    @GetMapping(
        *[
            "/",
            "/to-retry",
            "/next-retry",
            "/non-retryable",
            "/succeeded",
        ],
    )
    fun home(response: ServerHttpResponse): Mono<Void> {
        response.statusCode = HttpStatus.TEMPORARY_REDIRECT
        response.headers.location = INDEX_PAGE_URI
        return response.setComplete()
    }

    companion object {
        const val INDEX_PAGE = "/index.html"
        val INDEX_PAGE_URI = URI.create(INDEX_PAGE)
    }
}