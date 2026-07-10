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
import me.ahoo.wow.bi.BiScriptDiagnostic
import me.ahoo.wow.bi.BiScriptGenerator
import me.ahoo.wow.bi.BiScriptOptions
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

class GenerateBIScriptHandlerFunction(private val options: BiScriptOptions) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val result = BiScriptGenerator(options).generate(MetadataSearcher.localAggregates)
        logDiagnostics(result.diagnostics)

        return ServerResponse
            .ok()
            .contentType(APPLICATION_SQL_MEDIA_TYPE)
            .bodyValue(result.script)
    }

    internal fun logDiagnostics(diagnostics: List<BiScriptDiagnostic>) {
        diagnostics.forEach { diagnostic ->
            log.warn {
                "BI script diagnostic - code:[${diagnostic.code}], aggregate:[${diagnostic.aggregate}], " +
                    "path:[${diagnostic.path}], sourceType:[${diagnostic.sourceType}], " +
                    "decision:[${diagnostic.decision}], message:[${diagnostic.message}]."
            }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger(GenerateBIScriptHandlerFunction::class.java.name)
    }
}

class GenerateBIScriptHandlerFunctionFactory(private val options: BiScriptOptions) :
    NoMetadataRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT) {
    override fun create(
        contract: HttpRouteContract
    ): HandlerFunction<ServerResponse> {
        return GenerateBIScriptHandlerFunction(options)
    }
}
