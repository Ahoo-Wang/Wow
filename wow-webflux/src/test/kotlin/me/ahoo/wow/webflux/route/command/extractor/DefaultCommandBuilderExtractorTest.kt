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

package me.ahoo.wow.webflux.route.command.extractor

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.SpaceIdCapable.Companion.DEFAULT_SPACE_ID
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.rest.WowHeaders
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

/** The space of a command follows the aggregate's `@AggregateRoute(spaced = …)`, not the mere presence of the header. */
class DefaultCommandBuilderExtractorTest {
    private val extractor = DefaultCommandMessageExtractor(
        commandMessageFactory = SimpleCommandMessageFactory(NoOpValidator, SimpleCommandBuilderRewriterRegistry()),
        commandBuilderExtractor = DefaultCommandBuilderExtractor
    )

    private fun spaceIdOf(aggregateRouteMetadata: AggregateRouteMetadata<*>, spaceId: String?): String {
        val request = MockServerRequest.builder()
            .apply { spaceId?.let { header(WowHeaders.SPACE_ID, it) } }
            .build()
        var actual: String? = null
        extractor.extract(
            aggregateRouteMetadata = aggregateRouteMetadata,
            commandBody = MockCreateAggregate(id = generateGlobalId(), data = generateGlobalId()),
            request = request
        ).test()
            .consumeNextWith { actual = it.spaceId }
            .verifyComplete()
        return actual!!
    }

    @Test
    fun `a non-spaced aggregate ignores the space header and gets the default space`() {
        val routeMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
        routeMetadata.spaced.assert().isFalse()

        spaceIdOf(routeMetadata, "space-1").assert().isEqualTo(DEFAULT_SPACE_ID)
        spaceIdOf(routeMetadata, null).assert().isEqualTo(DEFAULT_SPACE_ID)
    }

    @Test
    fun `a spaced aggregate takes its space from the header`() {
        val routeMetadata = Order::class.java.aggregateRouteMetadata()
        routeMetadata.spaced.assert().isTrue()

        spaceIdOf(routeMetadata, "space-1").assert().isEqualTo("space-1")
        spaceIdOf(routeMetadata, null).assert().isEqualTo(DEFAULT_SPACE_ID)
    }
}
