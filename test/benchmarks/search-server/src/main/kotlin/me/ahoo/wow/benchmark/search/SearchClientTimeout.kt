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

package me.ahoo.wow.benchmark.search

import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * The response timeout of the client Spring Boot builds for this server's Elasticsearch store, as Operate ›
 * Elasticsearch tells an operator to set it ("A server that stops answering"). Boot's client has none, and its socket
 * timeout does not end an exchange on a connection the pool reuses: a paused Elasticsearch would hold the client's
 * connections until it answered, while the store's `operation-timeout` fails only the callers. A bean of its own beside
 * [SearchRequestsConfiguration]'s counting one: Spring Boot applies every `Rest5ClientBuilderCustomizer` bean, each of
 * its callbacks in turn.
 */
@Configuration(proxyBeanMethods = false)
class SearchClientTimeout {
    @Bean
    fun searchResponseTimeout(): Rest5ClientBuilderCustomizer = responseTimeout(RESPONSE_TIMEOUT)

    companion object {
        /**
         * The store's `operation-timeout`, which this server leaves at its default: a request is never ended while its
         * caller still waits for it, and none outlives its caller by more. Above the longest request the measurements
         * make (a search, the event index's access stamp).
         */
        val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** A customizer that sets the client's response timeout to [timeout]. */
        fun responseTimeout(timeout: Duration): Rest5ClientBuilderCustomizer {
            val bound = Timeout.ofMilliseconds(timeout.toMillis())
            return object : Rest5ClientBuilderCustomizer {
                override fun customize(builder: Rest5ClientBuilder) = Unit

                override fun customize(builder: RequestConfig.Builder) {
                    builder.setResponseTimeout(bound)
                }
            }
        }
    }
}
