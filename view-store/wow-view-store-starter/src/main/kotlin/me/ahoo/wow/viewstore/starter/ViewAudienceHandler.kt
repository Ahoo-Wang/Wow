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

import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.domain.view.View
import me.ahoo.wow.viewstore.domain.view.ViewState
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.command.toCommandResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * `PUT …/view/{id}/audience` before Wow's own route: a change to the audience the view already has is answered with
 * the view as it is, without a command. Wow records an event for every command, so sending it would move the
 * revision and turn other holders' next write into a conflict for a change that changed nothing.
 *
 * Only a request that would succeed is answered here: the view exists in this tenant, owner and application, is not
 * deleted, is at the expected version (when one is sent) and already has the audience. Every other request, and every
 * real change, goes to [dispatch] (Wow's handler of the route) with the same body, so its rules and errors are Wow's.
 */
class ViewAudienceHandler(
    private val stateAggregateRepository: StateAggregateRepository,
    private val dispatch: HandlerFunction<ServerResponse>,
    private val exceptionHandler: RequestExceptionHandler,
) : HandlerFunction<ServerResponse> {
    private val viewMetadata = View::class.java.aggregateRouteMetadata().aggregateMetadata

    @Suppress("UNCHECKED_CAST")
    private val viewStateMetadata = viewMetadata.state as StateAggregateMetadata<ViewState>

    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        request.bodyToMono(String::class.java)
            .defaultIfEmpty("")
            .flatMap { body ->
                unchanged(request, body)
                    .flatMap { Flux.just(it).toCommandResponse(request, exceptionHandler) }
                    .switchIfEmpty(Mono.defer { dispatch.handle(ServerRequest.from(request).body(body).build()) })
            }

    private fun unchanged(request: ServerRequest, body: String): Mono<CommandResult> {
        val audience = body.requestedAudience() ?: return Mono.empty()
        val appId = request.headers().firstHeader(ViewStoreService.APP_ID_HEADER)
        if (appId.isNullOrBlank()) {
            return Mono.empty()
        }
        val tenantId = request.pathVariable(ViewStorePaths.TENANT_ID)
        val ownerId = request.pathVariable(ViewStorePaths.OWNER_ID)
        val aggregateId = viewMetadata.namedAggregate.aggregateId(
            id = request.pathVariable(ViewStorePaths.ID),
            tenantId = tenantId
        )
        val expectedVersion = request.headers().firstHeader(CommandComponent.Header.AGGREGATE_VERSION)
        return stateAggregateRepository.load(aggregateId, viewStateMetadata)
            .filter {
                it.initialized &&
                    !it.deleted &&
                    it.ownerId == ownerId &&
                    it.state.appId == appId &&
                    it.state.audience == audience &&
                    (expectedVersion == null || expectedVersion.toIntOrNull() == it.version)
            }
            .map {
                val commandId = generateGlobalId()
                CommandResult(
                    id = generateGlobalId(),
                    waitCommandId = commandId,
                    stage = request.waitStage(),
                    contextName = aggregateId.contextName,
                    aggregateName = aggregateId.aggregateName,
                    tenantId = tenantId,
                    aggregateId = aggregateId.id,
                    aggregateVersion = it.version,
                    requestId = request.headers().firstHeader(CommandComponent.Header.REQUEST_ID)
                        .ifNotBlank { requestId -> requestId } ?: commandId,
                    commandId = commandId,
                    function = FUNCTION,
                )
            }
    }

    private fun String.requestedAudience(): ViewAudience? =
        runCatching { toObject(AudienceBody::class.java).audience }.getOrNull()

    internal data class AudienceBody(val audience: ViewAudience? = null)

    private fun ServerRequest.waitStage(): CommandStage =
        headers().firstHeader(CommandComponent.Header.WAIT_STAGE)
            ?.let { stage -> runCatching { CommandStage.valueOf(stage.uppercase()) }.getOrNull() }
            ?: CommandStage.PROCESSED

    private companion object {
        val FUNCTION = FunctionInfoData(
            functionKind = FunctionKind.COMMAND,
            contextName = ViewStoreService.SERVICE_NAME,
            processorName = ViewAudienceHandler::class.java.simpleName,
            name = "unchangedAudience",
        )
    }
}
