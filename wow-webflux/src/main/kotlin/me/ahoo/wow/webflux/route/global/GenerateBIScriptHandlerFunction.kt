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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ObjectMapStrategy
import me.ahoo.wow.bi.ScriptEngine
import me.ahoo.wow.bi.ScriptTemplateEngine
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.webflux.route.NoMetadataRouteHandlerFunctionFactorySupport
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

private val APPLICATION_SQL_MEDIA_TYPE = MediaType.parseMediaType("application/sql")

class GenerateBIScriptHandlerFunction(private val kafkaBootstrapServers: String, private val topicPrefix: String) :
    HandlerFunction<ServerResponse> {
    private var options: BiScriptOptions? = null

    constructor(options: BiScriptRouteOptions) : this(
        kafkaBootstrapServers = ScriptTemplateEngine.DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
        topicPrefix = ScriptTemplateEngine.DEFAULT_TOPIC_PREFIX,
    ) {
        this.options = options.toBiScriptOptions()
    }

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val result = options?.let {
            ScriptEngine.generateResult(
                namedAggregates = MetadataSearcher.localAggregates,
                options = it,
            )
        } ?: ScriptEngine.generateResult(
            namedAggregates = MetadataSearcher.localAggregates,
            kafkaBootstrapServers = kafkaBootstrapServers,
            topicPrefix = topicPrefix,
        )
        result.diagnostics.forEach { diagnostic ->
            log.warn {
                "BI script diagnostic - code:[${diagnostic.code}], severity:[${diagnostic.severity}], " +
                    "aggregate:[${diagnostic.aggregate}], path:[${diagnostic.path}], " +
                    "message:[${diagnostic.message}]."
            }
        }

        return ServerResponse
            .ok()
            .contentType(APPLICATION_SQL_MEDIA_TYPE)
            .bodyValue(result.script)
    }

    private fun BiScriptRouteOptions.toBiScriptOptions(): BiScriptOptions {
        val defaults = BiScriptOptions()
        return BiScriptOptions(
            database = database ?: defaults.database,
            consumerDatabase = consumerDatabase ?: defaults.consumerDatabase,
            cluster = cluster ?: defaults.cluster,
            installation = installation ?: defaults.installation,
            shard = shard ?: defaults.shard,
            replica = replica ?: defaults.replica,
            timezone = timezone ?: defaults.timezone,
            kafkaBootstrapServers = kafkaBootstrapServers ?: defaults.kafkaBootstrapServers,
            topicPrefix = topicPrefix ?: defaults.topicPrefix,
            maxExpansionDepth = maxExpansionDepth ?: defaults.maxExpansionDepth,
            unsupportedTypeStrategy = unsupportedTypeStrategy.toBiStrategy(defaults.unsupportedTypeStrategy),
            objectMapStrategy = objectMapStrategy.toBiStrategy(defaults.objectMapStrategy),
        ).validate()
    }

    private fun BiScriptRouteUnsupportedTypeStrategy?.toBiStrategy(
        default: UnsupportedTypeStrategy
    ): UnsupportedTypeStrategy = when (this) {
        null -> default
        BiScriptRouteUnsupportedTypeStrategy.FAIL -> UnsupportedTypeStrategy.FAIL
        BiScriptRouteUnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC -> UnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC
    }

    private fun BiScriptRouteObjectMapStrategy?.toBiStrategy(
        default: ObjectMapStrategy
    ): ObjectMapStrategy = when (this) {
        null -> default
        BiScriptRouteObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC -> ObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC
        BiScriptRouteObjectMapStrategy.FAIL -> ObjectMapStrategy.FAIL
    }

    private companion object {
        private val log = KotlinLogging.logger(GenerateBIScriptHandlerFunction::class.java.name)
    }
}

class GenerateBIScriptHandlerFunctionFactory(
    private val kafkaBootstrapServers: String = ScriptTemplateEngine.DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
    private val topicPrefix: String = ScriptTemplateEngine.DEFAULT_TOPIC_PREFIX,
) : NoMetadataRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT) {
    private var options: BiScriptRouteOptions? = null

    constructor(options: BiScriptRouteOptions) : this() {
        this.options = options
    }

    override fun create(
        contract: HttpRouteContract
    ): HandlerFunction<ServerResponse> {
        return options?.let(::GenerateBIScriptHandlerFunction)
            ?: GenerateBIScriptHandlerFunction(kafkaBootstrapServers, topicPrefix)
    }
}
