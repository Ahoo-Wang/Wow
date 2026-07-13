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
import me.ahoo.wow.bi.BiDeploymentInspector
import me.ahoo.wow.bi.BiScriptDiagnostic
import me.ahoo.wow.bi.BiScriptGenerator
import me.ahoo.wow.bi.BiScriptOperation
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.BiScriptResult
import me.ahoo.wow.bi.NoOpBiDeploymentInspector
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.bi.BiScriptDiagnosticResponse
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptResponse
import me.ahoo.wow.webflux.route.NoMetadataRouteHandlerFunctionFactorySupport
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

private val APPLICATION_SQL_MEDIA_TYPE = MediaType.parseMediaType("application/sql")

class GenerateBIScriptHandlerFunction(
    private val options: BiScriptOptions,
    private val deploymentInspector: BiDeploymentInspector = NoOpBiDeploymentInspector,
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return request.bodyToMono(BiScriptRequest::class.java)
            .switchIfEmpty(Mono.error(IllegalArgumentException("BI script request body must not be empty")))
            .flatMap { body ->
                generateResponse(
                    request = request,
                    requestOptions = body.toBiScriptOptions(options),
                    operation = body.toBiScriptOperation(),
                )
            }
    }

    private fun generateResponse(
        request: ServerRequest,
        requestOptions: BiScriptOptions,
        operation: BiScriptOperation,
    ): Mono<ServerResponse> = deploymentInspector.inspect(requestOptions).flatMap { inspection ->
        Mono.fromCallable {
            BiScriptGenerator(requestOptions).generate(MetadataSearcher.localAggregates, operation, inspection)
        }.subscribeOn(Schedulers.boundedElastic())
    }.flatMap { result ->
        logDiagnostics(result.diagnostics)
        val response = ServerResponse.ok()
            .header(DIAGNOSTIC_COUNT_HEADER, result.diagnostics.size.toString())
        if (request.prefersJson()) {
            response.contentType(MediaType.APPLICATION_JSON).bodyValue(result.toResponse())
        } else {
            response.contentType(APPLICATION_SQL_MEDIA_TYPE).bodyValue(result.script)
        }
    }

    private fun ServerRequest.prefersJson(): Boolean {
        val accepted = headers().accept()
            .filter { it.qualityValue > 0.0 }
            .sortedWith(
                compareByDescending<MediaType>(MediaType::getQualityValue)
                    .thenBy(MediaType::isWildcardType)
                    .thenBy(MediaType::isWildcardSubtype)
            )
        val preferred = accepted.firstOrNull { mediaType ->
            mediaType.isCompatibleWith(MediaType.APPLICATION_JSON) ||
                mediaType.subtype.endsWith("+json") ||
                mediaType.isCompatibleWith(APPLICATION_SQL_MEDIA_TYPE)
        } ?: return false
        if (preferred.isWildcardType || preferred.isWildcardSubtype) {
            return false
        }
        return preferred.isCompatibleWith(MediaType.APPLICATION_JSON) || preferred.subtype.endsWith("+json")
    }

    private fun BiScriptResult.toResponse(): BiScriptResponse = BiScriptResponse(
        script = script,
        destructive = destructive,
        diagnostics = diagnostics.map { diagnostic ->
            BiScriptDiagnosticResponse(
                code = diagnostic.code.name,
                aggregate = diagnostic.aggregate,
                path = diagnostic.path,
                sourceType = diagnostic.sourceType,
                decision = diagnostic.decision.name,
                message = diagnostic.message,
            )
        },
    )

    private fun logDiagnostics(diagnostics: List<BiScriptDiagnostic>) {
        diagnostics.forEach { diagnostic ->
            log.warn {
                "BI script diagnostic - code:[${diagnostic.code}], aggregate:[${diagnostic.aggregate}], " +
                    "path:[${diagnostic.path}], sourceType:[${diagnostic.sourceType}], " +
                    "decision:[${diagnostic.decision}], message:[${diagnostic.message}]."
            }
        }
    }

    private companion object {
        const val DIAGNOSTIC_COUNT_HEADER: String = "Wow-BI-Diagnostic-Count"
        private val log = KotlinLogging.logger(GenerateBIScriptHandlerFunction::class.java.name)
    }
}

class GenerateBIScriptHandlerFunctionFactory(
    private val options: BiScriptOptions,
    private val deploymentInspector: BiDeploymentInspector = NoOpBiDeploymentInspector,
) :
    NoMetadataRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT) {
    override fun create(
        contract: HttpRouteContract
    ): HandlerFunction<ServerResponse> {
        return GenerateBIScriptHandlerFunction(options, deploymentInspector)
    }
}
