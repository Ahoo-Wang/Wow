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

package me.ahoo.wow.spring.boot.starter.bi

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.BiDeploymentInspector
import me.ahoo.wow.bi.BiScriptDiagnostic
import me.ahoo.wow.bi.BiScriptOperation
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.BiScriptResult
import me.ahoo.wow.bi.BiScriptService
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.bi.BiScriptDiagnosticResponse
import me.ahoo.wow.openapi.contract.bi.BiScriptHeaders
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptResponse
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.NoMetadataRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.mapRequestBodyDecodingException
import me.ahoo.wow.webflux.route.preferredResponseMediaType
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.util.function.Predicate

private val APPLICATION_SQL_MEDIA_TYPE = MediaType.parseMediaType("application/sql")
private val SUPPORTED_RESPONSE_MEDIA_TYPES = listOf(APPLICATION_SQL_MEDIA_TYPE, MediaType.APPLICATION_JSON)
private val ALL_AGGREGATES: Predicate<NamedAggregate> = Predicate { true }

/**
 * Generates the BI script; [aggregateFilter] picks the local aggregates the script covers.
 */
internal class GenerateBIScriptHandlerFunction(
    private val options: BiScriptOptions,
    private val deploymentInspector: BiDeploymentInspector,
    private val exceptionHandler: RequestExceptionHandler,
    private val scriptService: BiScriptService = BiScriptService(deploymentInspector),
    private val aggregateFilter: Predicate<NamedAggregate> = ALL_AGGREGATES,
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return request.bodyToMono(BiScriptRequest::class.java).mapRequestBodyDecodingException()
            .switchIfEmpty(Mono.error(IllegalArgumentException("BI script request body must not be empty")))
            .flatMap { body ->
                body.requireAllowedInspectionScope(deploymentInspector)
                generateResponse(
                    requestOptions = body.toBiScriptOptions(options),
                    operation = body.toBiScriptOperation(),
                    responseMediaType = request.preferredResponseMediaType(SUPPORTED_RESPONSE_MEDIA_TYPES),
                )
            }
            .onErrorResume { error -> exceptionHandler.handle(request, error) }
    }

    private fun generateResponse(
        requestOptions: BiScriptOptions,
        operation: BiScriptOperation,
        responseMediaType: MediaType,
    ): Mono<ServerResponse> {
        return scriptService.generate(requestOptions, operation) {
            MetadataSearcher.localAggregates.filterTo(linkedSetOf()) { aggregateFilter.test(it) }
        }
            .flatMap { result ->
                logDiagnostics(result.diagnostics)
                val response = ServerResponse.ok()
                    .header(BiScriptHeaders.DIAGNOSTIC_COUNT, result.diagnostics.size.toString())
                if (responseMediaType == MediaType.APPLICATION_JSON) {
                    response.contentType(MediaType.APPLICATION_JSON).bodyValue(result.toResponse())
                } else {
                    response.contentType(APPLICATION_SQL_MEDIA_TYPE).bodyValue(result.script)
                }
            }
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
        private val log = KotlinLogging.logger(GenerateBIScriptHandlerFunction::class.java.name)
    }
}

/**
 * Creates the BI script route's handler; [aggregateFilter] picks the local aggregates the script covers.
 */
internal class GenerateBIScriptHandlerFunctionFactory(
    private val options: BiScriptOptions,
    private val deploymentInspector: BiDeploymentInspector,
    private val exceptionHandler: RequestExceptionHandler,
    private val aggregateFilter: Predicate<NamedAggregate> = ALL_AGGREGATES,
) :
    NoMetadataRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT) {

    override fun create(
        contract: HttpRouteContract
    ): HandlerFunction<ServerResponse> {
        return GenerateBIScriptHandlerFunction(
            options = options,
            deploymentInspector = deploymentInspector,
            exceptionHandler = exceptionHandler,
            aggregateFilter = aggregateFilter,
        )
    }
}
