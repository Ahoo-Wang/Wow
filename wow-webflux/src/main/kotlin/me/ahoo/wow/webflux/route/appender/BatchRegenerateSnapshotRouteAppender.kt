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

package me.ahoo.wow.webflux.route.appender

import io.swagger.v3.oas.annotations.enums.ParameterIn
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.FIRST_CURSOR_ID
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.modeling.asNamedAggregateString
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.route.AggregateRoutePathSpec.Companion.asAggregateIdRoutePathSpec
import me.ahoo.wow.webflux.ExceptionHandler
import me.ahoo.wow.webflux.route.BatchRegenerateSnapshotHandlerFunction
import me.ahoo.wow.webflux.route.BatchRegenerateSnapshotResult
import me.ahoo.wow.webflux.route.appender.RoutePaths.BATCH_REGENERATE_SNAPSHOT_CURSOR_ID
import me.ahoo.wow.webflux.route.appender.RoutePaths.BATCH_REGENERATE_SNAPSHOT_LIMIT
import org.springdoc.core.fn.builders.operation.Builder
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates
import java.util.function.Consumer

@Suppress("LongParameterList")
class BatchRegenerateSnapshotRouteAppender(
    private val currentContext: NamedBoundedContext,
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val routerFunctionBuilder: SpringdocRouteBuilder,
    private val snapshotRepository: SnapshotRepository,
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val exceptionHandler: ExceptionHandler,
) {
    fun append() {
        val routePrefix = aggregateMetadata.asAggregateIdRoutePathSpec(currentContext).aggregateNamePath
        routerFunctionBuilder
            .PUT(
                "$routePrefix/snapshot/{$BATCH_REGENERATE_SNAPSHOT_CURSOR_ID}/{$BATCH_REGENERATE_SNAPSHOT_LIMIT}",
                RequestPredicates.accept(MediaType.APPLICATION_JSON),
                BatchRegenerateSnapshotHandlerFunction(
                    aggregateMetadata = aggregateMetadata,
                    stateAggregateFactory = stateAggregateFactory,
                    eventStore = eventStore,
                    snapshotRepository = snapshotRepository,
                    exceptionHandler = exceptionHandler,
                ),
                batchRegenerateSnapshotOperation(),
            )
    }

    private fun batchRegenerateSnapshotOperation(): Consumer<Builder> {
        return Consumer<Builder> {
            it
                .tag(Wow.WOW)
                .tag(aggregateMetadata.asNamedAggregateString())
                .summary("Batch regenerate aggregate snapshot")
                .operationId("${aggregateMetadata.asNamedAggregateString()}.batchRegenerateSnapshot")
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(BATCH_REGENERATE_SNAPSHOT_CURSOR_ID)
                        .`in`(ParameterIn.PATH)
                        .implementation(String::class.java)
                        .example(FIRST_CURSOR_ID),
                )
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(BATCH_REGENERATE_SNAPSHOT_LIMIT)
                        .`in`(ParameterIn.PATH)
                        .implementation(Int::class.java)
                        .example(Int.MAX_VALUE.toString()),
                )
                .response(
                    org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder()
                        .responseCode(HttpStatus.OK.value().toString())
                        .description(HttpStatus.OK.reasonPhrase)
                        .implementation(BatchRegenerateSnapshotResult::class.java),
                )
        }
    }
}
