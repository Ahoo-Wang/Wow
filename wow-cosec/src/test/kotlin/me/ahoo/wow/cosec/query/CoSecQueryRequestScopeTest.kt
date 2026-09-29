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

package me.ahoo.wow.cosec.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.cosec.extractor.CoSecCommandBuilderExtractor.SPACE_ID_KEY
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class CoSecQueryRequestScopeTest {
    /** `@AggregateRoute(spaced = true)`; the mock aggregate is not spaced. */
    private val spacedMetadata = aggregateMetadata<Order, OrderState>()

    @Test
    fun `should resolve space id from the CoSec header for a spaced aggregate`() {
        val spaceId = generateGlobalId()
        val request = MockServerRequest.builder().header(SPACE_ID_KEY, spaceId).build()
        CoSecQueryRequestScope.resolve(spacedMetadata, request)
            .assert().isEqualTo(QueryScope(declared = SpaceIdFilter(spaceId)))
    }

    @Test
    fun `should prefer the Wow space header over the CoSec header for a spaced aggregate`() {
        val request = MockServerRequest.builder()
            .header(CommonComponent.Header.SPACE_ID, "wow-space")
            .header(SPACE_ID_KEY, "cosec-space")
            .build()
        CoSecQueryRequestScope.resolve(spacedMetadata, request)
            .assert().isEqualTo(QueryScope(declared = SpaceIdFilter("wow-space")))
    }

    @Test
    fun `should ignore both space headers for a non-spaced aggregate`() {
        val request = MockServerRequest.builder()
            .header(CommonComponent.Header.SPACE_ID, "wow-space")
            .header(SPACE_ID_KEY, generateGlobalId())
            .build()
        CoSecQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, request)
            .assert().isEqualTo(QueryScope.NONE)
    }
}
