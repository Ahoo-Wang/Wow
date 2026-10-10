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

package me.ahoo.wow.webflux.route.state

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventStore.Companion.DEFAULT_TAIL_VERSION
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.identity.aggregateId
import me.ahoo.wow.webflux.route.identity.identity
import me.ahoo.wow.webflux.route.policy.TracingPolicy
import me.ahoo.wow.webflux.route.policy.TracingRange
import me.ahoo.wow.webflux.route.policy.TracingRequest
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.Optional

class AggregateTracingHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: RequestExceptionHandler,
    private val tracingPolicy: TracingPolicy,
    private val admission: PointReadAdmission = PointReadAdmission.DISABLED,
) : HandlerFunction<ServerResponse> {
    /**
     * Resolves the range before the response starts, so a rejected range (the point-read admission cap) answers with
     * its own status, then replays it.
     */
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return Mono.defer {
            val aggregateId = request.identity(aggregateMetadata).aggregateId(aggregateMetadata)
            val tracingRequest = tracingPolicy.request(request)
            if (tracingRequest.limit == 0) {
                return@defer Flux.empty<ObjectNode>().toServerResponse(request, exceptionHandler)
            }
            range(aggregateId, tracingRequest).flatMap { range ->
                if (range.tailVersion < range.emitHeadVersion) {
                    return@flatMap Flux.empty<ObjectNode>().toServerResponse(request, exceptionHandler)
                }
                admission.requireTracingVersions(range.tailVersion - range.emitHeadVersion + 1)
                val states = replay(aggregateId, range)
                if (admission.enabled) {
                    admitted(request, states).toServerResponse(request, exceptionHandler)
                } else {
                    states.toServerResponse(request, exceptionHandler)
                }
            }
        }.onErrorResume {
            exceptionHandler.handle(request, it)
        }
    }

    /**
     * The versions to replay and emit. Only a `limit` (counted back from the stream's tail) or the admission cap needs
     * the stream's tail; otherwise the requested tail is replayed as is, without reading the tail first.
     */
    private fun range(aggregateId: AggregateId, tracingRequest: TracingRequest): Mono<TracingRange> {
        if (tracingRequest.limit == null && !admission.enabled) {
            return Mono.just(
                TracingRange(
                    replayHeadVersion = TracingPolicy.DEFAULT_HEAD_VERSION,
                    emitHeadVersion = tracingRequest.emitHeadVersion,
                    tailVersion = tracingRequest.tailVersion ?: DEFAULT_TAIL_VERSION,
                )
            )
        }
        return eventStore.last(aggregateId)
            .map { it.version }
            .defaultIfEmpty(TracingPolicy.EMPTY_TAIL_VERSION)
            .map(tracingRequest::toRange)
    }

    private fun replay(aggregateId: AggregateId, range: TracingRange): Flux<StateEvent<ObjectNode>> =
        AggregateTracingReplay.trace(
            stateAggregateMetadata = aggregateMetadata.state,
            stateAggregateFactory = stateAggregateFactory,
            eventStreams = eventStore.load(
                aggregateId = aggregateId,
                headVersion = range.replayHeadVersion,
                tailVersion = range.tailVersion,
            ),
            tracingRequest = TracingRequest(
                headVersion = range.emitHeadVersion,
                tailVersion = range.tailVersion,
                limit = null,
            ),
        )

    /** The admitted, masked [states], emitted only when the caller's scope admits every one of them. */
    private fun admitted(
        request: ServerRequest,
        states: Flux<StateEvent<ObjectNode>>,
    ): Flux<ObjectNode> = states.concatMap { state ->
        admission.read(aggregateMetadata, request, state, tracing = true)
            .map { Optional.of(it) }
            .defaultIfEmpty(Optional.empty())
    }.collectList().flatMapMany { records ->
        if (records.all { it.isPresent }) Flux.fromIterable(records.map { it.get() }) else Flux.empty()
    }
}
class AggregateTracingHandlerFunctionFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: RequestExceptionHandler,
    private val tracingPolicy: TracingPolicy,
    private val admission: PointReadAdmission = PointReadAdmission.DISABLED,
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.State.AGGREGATE_TRACING) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return AggregateTracingHandlerFunction(
            aggregateMetadata(metadata),
            stateAggregateFactory,
            eventStore,
            exceptionHandler,
            tracingPolicy,
            admission,
        )
    }
}
