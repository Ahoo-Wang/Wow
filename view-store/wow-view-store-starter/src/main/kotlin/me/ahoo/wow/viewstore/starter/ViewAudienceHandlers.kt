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
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.ViewStoreService.SHARED_OWNER_ID
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.view.ClaimView
import me.ahoo.wow.viewstore.domain.view.View
import me.ahoo.wow.viewstore.domain.view.ViewState
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandHandler
import me.ahoo.wow.webflux.route.command.toCommandResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * The two audience routes, both secured by their path (the gateway lets a caller use `owner/{ownerId}` only as
 * itself, and the server trusts the path):
 * - [share], `PUT …/owner/{ownerId}/view/{id}/share`, sent to the view's personal path: before Wow's generated route
 *   of `ShareView`, which moves the view to `(shared)`;
 * - [claim], `PUT …/owner/{ownerId}/view/{id}/claim`, sent to the caller's own personal path: the view store's route,
 *   which moves a shared view to that `{ownerId}`.
 *
 * Wow checks a command's owner against the view's, so a claim cannot be dispatched with the claiming owner as the
 * command's owner. [claim] dispatches `ClaimView(toOwnerId = {ownerId})` with the owner `(shared)` instead: only a
 * shared view passes Wow's owner check, and a personal view of anyone else is refused by Wow
 * (`IllegalAccessOwnerAggregate`) without the domain having to tell the two apart.
 *
 * Both answer a change to the audience the view already has with the view as it is, without a command: Wow records
 * an event for every command, so sending it would move the revision and turn other holders' next write into a
 * conflict for a change that changed nothing. Only a request that would succeed is answered that way: the view
 * exists in this tenant, owner and application, is not deleted, is at the expected version (when one is sent) and
 * already has the audience. Every other request goes to the command, so its rules and errors are Wow's and the
 * domain's.
 */
internal class ViewAudienceHandlers(
    private val stateAggregateRepository: StateAggregateRepository,
    private val shareDispatch: HandlerFunction<ServerResponse>,
    private val commandHandler: CommandHandler,
    private val exceptionHandler: RequestExceptionHandler,
) {
    private val viewRouteMetadata = View::class.java.aggregateRouteMetadata()
    private val viewMetadata = viewRouteMetadata.aggregateMetadata

    @Suppress("UNCHECKED_CAST")
    private val viewStateMetadata = viewMetadata.state as StateAggregateMetadata<ViewState>

    fun share(request: ServerRequest): Mono<ServerResponse> =
        unchanged(request, ViewAudience.SHARED)
            .flatMap { Flux.just(it).toCommandResponse(request, exceptionHandler) }
            .switchIfEmpty(Mono.defer { shareDispatch.handle(request) })

    fun claim(request: ServerRequest): Mono<ServerResponse> =
        unchanged(request, ViewAudience.PERSONAL)
            .flatMap { Flux.just(it).toCommandResponse(request, exceptionHandler) }
            .switchIfEmpty(Mono.defer { dispatchClaim(request) })

    private fun dispatchClaim(request: ServerRequest): Mono<ServerResponse> {
        val command = ClaimView(toOwnerId = request.pathVariable(ViewStorePaths.OWNER_ID))
        val sharedPathVariables = request.pathVariables() + (ViewStorePaths.OWNER_ID to SHARED_OWNER_ID)
        val sharedRequest = ServerRequest.from(request)
            .attribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, sharedPathVariables)
            .build()
        return commandHandler.handle(sharedRequest, command, viewRouteMetadata)
            .toCommandResponse(request, exceptionHandler)
    }

    private fun unchanged(request: ServerRequest, audience: ViewAudience): Mono<CommandResult> {
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

    private fun ServerRequest.waitStage(): CommandStage =
        headers().firstHeader(CommandComponent.Header.WAIT_STAGE)
            ?.let { stage -> runCatching { CommandStage.valueOf(stage.uppercase()) }.getOrNull() }
            ?: CommandStage.PROCESSED

    private companion object {
        val FUNCTION = FunctionInfoData(
            functionKind = FunctionKind.COMMAND,
            contextName = ViewStoreService.SERVICE_NAME,
            processorName = ViewAudienceHandlers::class.java.simpleName,
            name = "unchangedAudience",
        )
    }
}
