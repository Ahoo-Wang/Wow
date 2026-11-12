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

package me.ahoo.wow.example.eshop.catalog.server

import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration

/**
 * The response timeout of the client Spring Boot builds of `spring.elasticsearch.*`, which the catalog's Elasticsearch
 * store writes and reads through (its `search` and `search-only` profiles), as Operate › Elasticsearch tells an operator
 * to set it ("A server that stops answering"). Boot's client has none, and its socket timeout does not end an exchange
 * on a connection the pool reuses: a paused Elasticsearch would hold the client's connections until it answered, while
 * the store's `operation-timeout` fails only the callers. With it, a request with no answer within [responseTimeout]
 * fails and frees its connection.
 */
@Configuration(proxyBeanMethods = false)
class CatalogElasticsearch {
    @Bean
    fun elasticsearchResponseTimeout(environment: Environment): Rest5ClientBuilderCustomizer {
        val timeout = Timeout.ofMilliseconds(responseTimeout(environment).toMillis())
        return object : Rest5ClientBuilderCustomizer {
            override fun customize(builder: Rest5ClientBuilder) = Unit

            override fun customize(builder: RequestConfig.Builder) {
                builder.setResponseTimeout(timeout)
            }
        }
    }

    companion object {
        /** The key that sets the response timeout. */
        const val RESPONSE_TIMEOUT: String = "eshop.catalog.elasticsearch.response-timeout"

        /**
         * The response timeout unless [RESPONSE_TIMEOUT] sets another: the store's `operation-timeout` by default, so a
         * request is never ended while its caller still waits for it, and no request outlives its caller by more. Keep
         * it at or above the store's `operation-timeout`, and above the longest request the store makes (a search,
         * the event index's access stamp).
         */
        val DEFAULT_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** The response timeout [environment] sets, or [DEFAULT_RESPONSE_TIMEOUT]. */
        fun responseTimeout(environment: Environment): Duration =
            Binder.get(environment).bind(RESPONSE_TIMEOUT, Duration::class.java).orElse(null) ?: DEFAULT_RESPONSE_TIMEOUT
    }
}
