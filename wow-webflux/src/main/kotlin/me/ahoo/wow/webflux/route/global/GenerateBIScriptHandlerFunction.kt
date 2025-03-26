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

package me.ahoo.wow.webflux.route.global

import me.ahoo.wow.bi.MessageHeaderSqlType
import me.ahoo.wow.bi.ScriptEngine
import me.ahoo.wow.bi.ScriptTemplateEngine
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.openapi.global.GenerateBIScriptRouteSpec
import me.ahoo.wow.openapi.global.GenerateBIScriptRouteSpecFactory.Companion.BI_HEADER_TYPE_HEADER
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.util.*

private val APPLICATION_SQL_MEDIA_TYPE = MediaType.parseMediaType("application/sql")

class GenerateBIScriptHandlerFunction(private val kafkaBootstrapServers: String, private val topicPrefix: String) :
    HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val headerType = request.headers().firstHeader(BI_HEADER_TYPE_HEADER).ifNotBlank { stage ->
            MessageHeaderSqlType.valueOf(stage.uppercase(Locale.getDefault()))
        } ?: MessageHeaderSqlType.MAP

        val script = ScriptEngine.generate(
            namedAggregates = MetadataSearcher.localAggregates,
            kafkaBootstrapServers = kafkaBootstrapServers,
            topicPrefix = topicPrefix,
            headerType = headerType
        )

        return ServerResponse
            .ok()
            .contentType(APPLICATION_SQL_MEDIA_TYPE)
            .bodyValue(script)
    }
}

class GenerateBIScriptHandlerFunctionFactory(
    private val kafkaBootstrapServers: String = ScriptTemplateEngine.DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
    private val topicPrefix: String = ScriptTemplateEngine.DEFAULT_TOPIC_PREFIX,
) : RouteHandlerFunctionFactory<GenerateBIScriptRouteSpec> {
    override val supportedSpec: Class<GenerateBIScriptRouteSpec>
        get() = GenerateBIScriptRouteSpec::class.java

    override fun create(spec: GenerateBIScriptRouteSpec): HandlerFunction<ServerResponse> {
        return GenerateBIScriptHandlerFunction(kafkaBootstrapServers, topicPrefix)
    }
}
