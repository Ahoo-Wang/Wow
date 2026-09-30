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

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.event.AggregateDeleted
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.materialize
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.dsl.FilterDsl
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.query
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesInput
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesView
import me.ahoo.wow.viewstore.api.view.ViewAudienceChanged
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.viewstore.domain.preferences.ViewPreferences
import me.ahoo.wow.viewstore.domain.preferences.ViewPreferencesIds
import me.ahoo.wow.viewstore.domain.preferences.ViewPreferencesState
import me.ahoo.wow.viewstore.domain.view.View
import me.ahoo.wow.viewstore.domain.view.ViewState
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandHandler
import me.ahoo.wow.webflux.route.command.toCommandResponse
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty

/**
 * The view store's own routes, beside the ones Wow generates from the aggregates.
 */
private const val MAX_REPLAY_CANDIDATES = 20

class ViewStoreHandlers(
    private val systemViewProvider: SystemViewProvider,
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandHandler: CommandHandler,
    private val viewEventStreamQueryGateway: () -> EventStreamQueryGateway,
    private val exceptionHandler: RequestExceptionHandler,
) {
    private val viewRouteMetadata = View::class.java.aggregateRouteMetadata()
    private val preferencesRouteMetadata = ViewPreferences::class.java.aggregateRouteMetadata()

    @Suppress("UNCHECKED_CAST")
    private val viewStateMetadata = viewRouteMetadata.aggregateMetadata.state as StateAggregateMetadata<ViewState>

    @Suppress("UNCHECKED_CAST")
    private val preferencesStateMetadata =
        preferencesRouteMetadata.aggregateMetadata.state as StateAggregateMetadata<ViewPreferencesState>

    /** `GET …/owner/(shared)/system-views?definitionId=`: the server's system views; all of them without one. */
    fun systemViews(request: ServerRequest): Mono<ServerResponse> {
        val definitionId = request.queryParam(ViewStorePaths.DEFINITION_ID).orElse(null)
        return Mono.fromCallable { request.sharedScope() }
            .flatMapMany { (tenantId, appId) -> systemViewProvider.systemViews(tenantId, appId) }
            .filter { definitionId.isNullOrBlank() || it.definitionId == definitionId }
            .collectList()
            .toServerResponse(request, exceptionHandler)
    }

    /** `GET …/owner/(shared)/system-views/{id}`. */
    fun systemView(request: ServerRequest): Mono<ServerResponse> {
        val id = request.pathVariable(ViewStorePaths.ID)
        return Mono.fromCallable { request.sharedScope() }
            .flatMapMany { (tenantId, appId) -> systemViewProvider.systemViews(tenantId, appId) }
            .filter { it.id == id }
            .next()
            .switchIfEmpty { Mono.error(NotFoundResourceException("System view [$id] is not found.")) }
            .toServerResponse(request, exceptionHandler)
    }

    /**
     * `GET …/definitions/{definitionId}/preferences`: the owner's preferences in the request's application, or empty
     * ones at version `0` when they were never written.
     */
    fun getPreferences(request: ServerRequest): Mono<ServerResponse> {
        val definitionId = request.pathVariable(ViewStorePaths.DEFINITION_ID)
        return Mono.fromCallable { request.preferencesAggregateId(definitionId) }
            .flatMap { aggregateId ->
                stateAggregateRepository.load(aggregateId, preferencesStateMetadata)
            }
            .map { stateAggregate ->
                if (!stateAggregate.initialized || stateAggregate.deleted) {
                    return@map ViewPreferencesView(definitionId = definitionId)
                }
                val state = stateAggregate.state
                ViewPreferencesView(
                    definitionId = state.definitionId,
                    order = state.order,
                    defaultInstanceId = state.defaultInstanceId,
                    autoRun = state.autoRun,
                    lastTabs = state.lastTabs,
                    version = stateAggregate.version,
                )
            }
            .toServerResponse(request, exceptionHandler)
    }

    /**
     * `PUT …/definitions/{definitionId}/preferences`: dispatches `SetViewPreferences` to the derived id, with the
     * request's `Command-Request-Id`, `Command-Aggregate-Version` (`0` is "never written") and wait headers, and
     * answers the command result as a command route does.
     */
    fun setPreferences(request: ServerRequest): Mono<ServerResponse> {
        val definitionId = request.pathVariable(ViewStorePaths.DEFINITION_ID)
        return request.bodyToMono(ViewPreferencesInput::class.java)
            .switchIfEmpty { Mono.error(IllegalArgumentException("Preferences can not be empty.")) }
            .flatMap { input ->
                val aggregateId = request.preferencesAggregateId(definitionId)
                requireNeverWritten(request, aggregateId).thenReturn(input to aggregateId)
            }
            .flatMapMany { (input, aggregateId) ->
                val commandRequest = ServerRequest.from(request)
                    .headers { it.set(CommandComponent.Header.AGGREGATE_ID, aggregateId.id) }
                    .build()
                commandHandler.handle(commandRequest, input.toCommand(definitionId), preferencesRouteMetadata)
            }
            .toCommandResponse(request, exceptionHandler)
    }

    /**
     * Wow reads an expected version of `0` as a create, which on preferences already written fails as a duplicate
     * aggregate. The port reads it as "never written", so a `0` against written preferences is the version conflict
     * any other stale version is.
     */
    private fun requireNeverWritten(request: ServerRequest, aggregateId: AggregateId): Mono<Void> {
        val expectedVersion = request.headers().firstHeader(CommandComponent.Header.AGGREGATE_VERSION)?.toIntOrNull()
        if (expectedVersion != Version.UNINITIALIZED_VERSION) {
            return Mono.empty()
        }
        return stateAggregateRepository.load(aggregateId, preferencesStateMetadata)
            .filter { it.initialized }
            .flatMap {
                Mono.error<Void>(
                    WowException(
                        ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT,
                        "The preferences were written: expected version [0], actual [${it.version}]."
                    )
                )
            }
    }

    /**
     * `GET …/view/requests/{requestId}`: the view as the write with this request id left it, found only among this
     * path's tenant and owner and the request's application; `204` when that write deleted it (a view of another
     * application answers not found in both cases).
     *
     * A claim is dispatched with the owner `(shared)` but is the claiming owner's write, so it is replayed on the path
     * of the owner it moved the view to, and not on `(shared)`.
     */
    fun replay(request: ServerRequest): Mono<ServerResponse> {
        val requestId = request.pathVariable(ViewStorePaths.REQUEST_ID)
        val tenantId = request.pathVariable(ViewStorePaths.TENANT_ID)
        val ownerId = request.pathVariable(ViewStorePaths.OWNER_ID)
        return Mono.fromCallable { request.requiredAppId() }.flatMap { appId ->
            listQuery {
                limit(MAX_REPLAY_CANDIDATES)
                filter {
                    tenantId(tenantId)
                    MessageRecords.REQUEST_ID eq requestId
                    writtenBy(ownerId)
                }
            }.query(viewEventStreamQueryGateway())
                .filter { it.writtenBy() == ownerId }
                .next()
                .switchIfEmpty { Mono.error(NotFoundResourceException("Request [$requestId] is not found.")) }
                .flatMap { eventStream ->
                    val deleted = eventStream.body.any { it.body is AggregateDeleted }
                    // A delete leaves no state to answer with, so its application is the one before it.
                    val version = if (deleted) eventStream.version - 1 else eventStream.version
                    val view = loadView(eventStream.aggregateId, version)
                        .filter { it.state.appId == appId }
                        .switchIfEmpty { Mono.error(NotFoundResourceException("Request [$requestId] is not found.")) }
                    if (deleted) {
                        view.flatMap { ServerResponse.noContent().build() }
                    } else {
                        view.toServerResponse(request, exceptionHandler)
                    }
                }
        }.onErrorResume { exceptionHandler.handle(request, it) }
    }

    /**
     * The writes of [ownerId], as [writtenBy] reads them, selected by the query itself: the candidates are bounded, so
     * another owner's writes with the same request id must not take their place.
     */
    private fun FilterDsl.writtenBy(ownerId: String) {
        if (ownerId == ViewStoreService.SHARED_OWNER_ID) {
            ownerId(ownerId)
            nor { MessageRecords.BODY.elementMatch { claimEvent() } }
            return
        }
        or {
            ownerId(ownerId)
            and {
                ownerId(ViewStoreService.SHARED_OWNER_ID)
                MessageRecords.BODY.elementMatch {
                    claimEvent()
                    "${MessageRecords.BODY}.${ViewAudienceChanged::toOwnerId.name}" eq ownerId
                }
            }
        }
    }

    /** An event of a stream's `body` that is a claim: its audience changed to personal. */
    private fun FilterDsl.claimEvent() {
        MessageRecords.BODY_TYPE eq ViewAudienceChanged::class.java.name
        "${MessageRecords.BODY}.${ViewAudienceChanged::audience.name}" eq ViewAudience.PERSONAL.value
    }

    /** The owner whose write this is: the claiming owner for a claim, the stream's owner otherwise. */
    private fun DomainEventStream.writtenBy(): String {
        val claimedBy = body.map { it.body }.filterIsInstance<ViewAudienceChanged>()
            .firstOrNull { it.audience == ViewAudience.PERSONAL }?.toOwnerId
        return if (ownerId == ViewStoreService.SHARED_OWNER_ID && claimedBy != null) claimedBy else ownerId
    }

    private fun loadView(aggregateId: AggregateId, version: Int): Mono<MaterializedSnapshot<ViewState>> =
        stateAggregateRepository.load(aggregateId, viewStateMetadata, version)
            .filter { it.initialized }
            .map { SimpleSnapshot(it).materialize { state -> state } }

    private fun ServerRequest.requiredAppId(): String {
        val appId = headers().firstHeader(ViewStoreService.APP_ID_HEADER)
        if (appId.isNullOrBlank()) {
            throw ViewStoreException.appRequired()
        }
        return appId
    }

    /** The tenant and application of a system view route, which only the shared owner serves. */
    private fun ServerRequest.sharedScope(): Pair<String, String> {
        if (pathVariable(ViewStorePaths.OWNER_ID) != ViewStoreService.SHARED_OWNER_ID) {
            throw NotFoundResourceException("System views are served under the shared owner only.")
        }
        return pathVariable(ViewStorePaths.TENANT_ID) to requiredAppId()
    }

    private fun ServerRequest.preferencesAggregateId(definitionId: String): AggregateId {
        val tenantId = pathVariable(ViewStorePaths.TENANT_ID)
        val ownerId = pathVariable(ViewStorePaths.OWNER_ID)
        val id = ViewPreferencesIds.of(tenantId, ownerId, requiredAppId(), definitionId)
        return preferencesRouteMetadata.aggregateMetadata.namedAggregate.aggregateId(id = id, tenantId = tenantId)
    }
}
