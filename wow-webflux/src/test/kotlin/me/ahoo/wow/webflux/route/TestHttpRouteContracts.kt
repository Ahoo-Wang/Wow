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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA

internal fun testGlobalRouteContract(handlerKey: String): HttpRouteContract {
    return testRouteContract(handlerKey = handlerKey)
}

internal fun testAggregateRouteContract(
    handlerKey: String,
    aggregateRouteMetadata: AggregateRouteMetadata<*> =
        MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
): HttpRouteContract {
    return testRouteContract(
        handlerKey = handlerKey,
        handlerMetadata = HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata)
    )
}

internal fun testCommandRouteContract(
    handlerKey: String,
    aggregateRouteMetadata: AggregateRouteMetadata<*>,
    commandRouteMetadata: CommandRouteMetadata<*>
): HttpRouteContract {
    return testRouteContract(
        handlerKey = handlerKey,
        handlerMetadata = HttpRouteHandlerMetadata.Command(aggregateRouteMetadata, commandRouteMetadata)
    )
}

private fun testRouteContract(
    handlerKey: String,
    handlerMetadata: HttpRouteHandlerMetadata = HttpRouteHandlerMetadata.None
): HttpRouteContract {
    return HttpRouteContract(
        routeId = "test.route",
        method = Https.Method.GET,
        path = "/test",
        handlerKey = handlerKey,
        handlerMetadata = handlerMetadata
    )
}
