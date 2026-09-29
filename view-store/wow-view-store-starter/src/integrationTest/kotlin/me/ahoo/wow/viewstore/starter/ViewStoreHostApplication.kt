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

package me.ahoo.wow.viewstore.starter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import java.security.Principal

/**
 * A host service of another context (the example's) with the view store starter embedded, as a business service
 * would add it.
 */
@SpringBootApplication
class ViewStoreHostApplication {
    companion object {
        /** Stands in for the authentication a gateway performs: names the request's principal. */
        const val USER_HEADER = "X-Test-User"
    }

    @Bean
    fun testPrincipalFilter(): WebFilter = WebFilter { exchange, chain ->
        val user = exchange.request.headers.getFirst(USER_HEADER)
        if (user == null) {
            chain.filter(exchange)
        } else {
            chain.filter(exchange.mutate().principal(Mono.just(Principal { user })).build())
        }
    }
}
