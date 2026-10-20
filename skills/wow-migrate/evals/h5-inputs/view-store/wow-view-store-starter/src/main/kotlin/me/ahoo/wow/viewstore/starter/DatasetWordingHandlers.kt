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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.wording.ClearDatasetWording
import me.ahoo.wow.viewstore.api.wording.DatasetWordingInput
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.viewstore.domain.wording.DatasetWording
import me.ahoo.wow.viewstore.domain.wording.DatasetWordingIds
import me.ahoo.wow.viewstore.domain.wording.DatasetWordingState
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandHandler
import me.ahoo.wow.webflux.route.command.toCommandResponse
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty

/**
 * The wording routes (view engine D86): `PUT` and `DELETE …/owner/(shared)/datasets/{dataset}/wording`. A
 * dataset's wording is the whole tenant's, so these are served under the shared owner only; who may send them is the
 * security gateway's to decide. Its id is derived from the tenant, the request's application and the dataset
 * ([DatasetWordingIds]); the wording is read through Wow's snapshot queries of `dataset_wording`, which the query
 * policy keeps in the request's application.
 */
internal class DatasetWordingHandlers(
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandHandler: CommandHandler,
    private val exceptionHandler: RequestExceptionHandler,
) {
    private val routeMetadata = DatasetWording::class.java.aggregateRouteMetadata()

    @Suppress("UNCHECKED_CAST")
    private val stateMetadata = routeMetadata.aggregateMetadata.state as StateAggregateMetadata<DatasetWordingState>

    /**
     * `PUT …/datasets/{dataset}/wording`: dispatches `SetDatasetWording` to the derived id, with the request's
     * `Command-Request-Id`, `Command-Aggregate-Version` (`0` is "never written") and wait headers.
     */
    fun set(request: ServerRequest): Mono<ServerResponse> {
        val dataset = request.pathVariable(ViewStorePaths.DATASET)
        return request.bodyToMono(DatasetWordingInput::class.java)
            .switchIfEmpty { Mono.error(IllegalArgumentException("Wording can not be empty.")) }
            .flatMap { input ->
                val aggregateId = request.wordingAggregateId(dataset)
                requireNeverWritten(request, aggregateId).thenReturn(input to aggregateId)
            }
            .flatMapMany { (input, aggregateId) ->
                commandHandler.handle(request.addressing(aggregateId), input.toCommand(dataset), routeMetadata)
            }
            .toCommandResponse(request, exceptionHandler)
    }

    /** `DELETE …/datasets/{dataset}/wording`: dispatches `ClearDatasetWording` with the expected version. */
    fun clear(request: ServerRequest): Mono<ServerResponse> {
        val dataset = request.pathVariable(ViewStorePaths.DATASET)
        return Mono.fromCallable { request.wordingAggregateId(dataset) }
            .flatMapMany { aggregateId ->
                commandHandler.handle(request.addressing(aggregateId), ClearDatasetWording, routeMetadata)
            }
            .toCommandResponse(request, exceptionHandler)
    }

    /** As for preferences: an expected version of `0` against written wording is a version conflict. */
    private fun requireNeverWritten(request: ServerRequest, aggregateId: AggregateId): Mono<Void> {
        val expectedVersion = request.headers().firstHeader(CommandHeaders.AGGREGATE_VERSION)?.toIntOrNull()
        if (expectedVersion != Version.UNINITIALIZED_VERSION) {
            return Mono.empty()
        }
        return stateAggregateRepository.load(aggregateId, stateMetadata)
            .filter { it.initialized && !it.deleted }
            .flatMap {
                Mono.error<Void>(
                    WowException(
                        ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT,
                        "The wording was written: expected version [0], actual [${it.version}]."
                    )
                )
            }
    }

    private fun ServerRequest.addressing(aggregateId: AggregateId): ServerRequest = ServerRequest.from(this)
        .headers { it.set(CommandHeaders.AGGREGATE_ID, aggregateId.id) }
        .build()

    private fun ServerRequest.wordingAggregateId(dataset: String): AggregateId {
        if (pathVariable(ViewStorePaths.OWNER_ID) != ViewStoreService.SHARED_OWNER_ID) {
            throw NotFoundResourceException("Dataset wording is served under the shared owner only.")
        }
        val appId = headers().firstHeader(ViewStoreService.APP_ID_HEADER)
        if (appId.isNullOrBlank()) {
            throw ViewStoreException.appRequired()
        }
        val tenantId = pathVariable(ViewStorePaths.TENANT_ID)
        val id = DatasetWordingIds.of(tenantId, appId, dataset)
        return routeMetadata.aggregateMetadata.namedAggregate.aggregateId(id = id, tenantId = tenantId)
    }
}
