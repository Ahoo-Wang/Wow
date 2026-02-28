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

package me.ahoo.wow.elasticsearch

import me.ahoo.wow.tck.container.ElasticsearchLauncher
import me.ahoo.wow.tck.container.ElasticsearchLauncher.ELASTIC_PWD
import org.springframework.data.elasticsearch.client.ClientConfiguration
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.rest5_client.Rest5Clients
import org.springframework.data.elasticsearch.support.HttpHeaders
import java.time.Duration

object ReactiveElasticsearchClients {
    fun createReactiveElasticsearchClient(): ReactiveElasticsearchClient {
        val httpHeaders = HttpHeaders()
//        httpHeaders["X-Elastic-Product"] = listOf("Elasticsearch")
//        httpHeaders["Content-Type"] = listOf("application/json")
//        httpHeaders["Accept"] = listOf("application/vnd.elasticsearch+json; compatible-with=8")
        val clientConfiguration =
            ClientConfiguration
                .builder()
                .connectedTo(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.httpHostAddress)
                .usingSsl(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.createSslContextFromCa())
                .withBasicAuth("elastic", ELASTIC_PWD)
                .withSocketTimeout(Duration.ofSeconds(30))
                .withConnectTimeout(Duration.ofSeconds(5))
                .withDefaultHeaders(httpHeaders)
                .build()
        val rest5Client = Rest5Clients.getRest5Client(clientConfiguration)
        val elasticsearchClient =
            ElasticsearchClients.createReactive(
                rest5Client,
                null,
                WowJsonpMapper,
            )

        return elasticsearchClient
    }
}
