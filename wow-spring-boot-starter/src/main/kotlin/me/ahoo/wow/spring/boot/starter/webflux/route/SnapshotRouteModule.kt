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

package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.policy.BatchExecutionPolicy
import me.ahoo.wow.webflux.route.snapshot.BatchRegenerateSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.RegenerateSnapshotHandlerFunctionFactory

class SnapshotRouteModule(
    stateAggregateFactory: StateAggregateFactory,
    eventStore: EventStore,
    snapshotStore: SnapshotStore,
    exceptionHandler: RequestExceptionHandler,
    batchExecutionPolicy: BatchExecutionPolicy
) : WebFluxRouteModule {
    override val httpFactories: List<HttpRouteHandlerFunctionFactory> = listOf(
        RegenerateSnapshotHandlerFunctionFactory(
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            snapshotStore = snapshotStore,
            exceptionHandler = exceptionHandler
        ),
        BatchRegenerateSnapshotHandlerFunctionFactory(
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            snapshotStore = snapshotStore,
            exceptionHandler = exceptionHandler,
            batchExecutionPolicy = batchExecutionPolicy
        ),
    )
}
