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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HttpRouteHandlerMetadataSupportTest {

    @Test
    fun `should require aggregate handler metadata`() {
        val metadata = HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata)

        metadata.requireAggregateHandlerMetadata("aggregate.handler").assert().isSameAs(metadata)
    }

    @Test
    fun `should reject non aggregate handler metadata`() {
        val error = assertThrows<IllegalStateException> {
            HttpRouteHandlerMetadata.None.requireAggregateHandlerMetadata("aggregate.handler")
        }

        error.message.assert().contains("handlerKey:[aggregate.handler]")
        error.message.assert().contains(HttpRouteHandlerMetadata.Aggregate::class.java.name)
    }

    @Test
    fun `should require command handler metadata`() {
        val metadata = HttpRouteHandlerMetadata.Command(
            aggregateRouteMetadata = aggregateRouteMetadata,
            commandRouteMetadata = MockCreateAggregate::class.java.commandRouteMetadata()
        )

        metadata.requireCommandHandlerMetadata("command.handler").assert().isSameAs(metadata)
    }

    @Test
    fun `should reject non command handler metadata`() {
        val error = assertThrows<IllegalStateException> {
            HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata)
                .requireCommandHandlerMetadata("command.handler")
        }

        error.message.assert().contains("handlerKey:[command.handler]")
        error.message.assert().contains(HttpRouteHandlerMetadata.Command::class.java.name)
    }

    private val aggregateRouteMetadata =
        MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
}
