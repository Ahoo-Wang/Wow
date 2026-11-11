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
import me.ahoo.wow.benchmark.transfer.TransferServer
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder
import org.springframework.boot.SpringApplication
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The benchmark server ([TransferServer]) with an Elasticsearch store, for the measurements of the search and the
 * sink: its profile `search` (`application-search.yaml`) names the store and puts the account's
 * events on it, which starts the account's Elasticsearch sink. The benchmark server's own distribution stays without
 * Elasticsearch, so no other suite's classpath, start-up or memory changes.
 */
fun main(args: Array<String>) {
    SpringApplication(TransferServer::class.java, SearchRequestsConfiguration::class.java)
        .apply { setAdditionalProfiles(SEARCH_PROFILE) }
        .run(*args)
}

/** The profile of this server's Elasticsearch store. */
const val SEARCH_PROFILE: String = "search"

/**
 * [SearchRequests] on the client Spring Boot builds for the Elasticsearch store: its HTTP client counts each request
 * it sends, and the counts are logged once a second and on stop.
 */
@Configuration(proxyBeanMethods = false)
class SearchRequestsConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun searchRequests(): SearchRequests = SearchRequests()

    @Bean
    fun searchRequestsCounting(requests: SearchRequests): Rest5ClientBuilderCustomizer =
        object : Rest5ClientBuilderCustomizer {
            override fun customize(builder: Rest5ClientBuilder) = Unit

            override fun customize(builder: HttpAsyncClientBuilder) {
                builder.addRequestInterceptorFirst(requests)
            }
        }
}
