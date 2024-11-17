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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.openapi.BatchResult
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.kotlin.test.test

class BatchResultsKtTest {

    @Test
    fun toBatchResult() {
        // Arrange
        val flux = Flux.just(
            MOCK_AGGREGATE_METADATA.aggregateId("id1"),
            MOCK_AGGREGATE_METADATA.aggregateId("id2")
        ).cast<AggregateId>(AggregateId::class.java)
        val afterId = "id0"

        // Act
        flux.toBatchResult(afterId)
            .test()
            .expectNext(BatchResult("id2", 2))
            .verifyComplete()
    }

    @Test
    fun toBatchResultWhenError() {
        // Arrange
        val flux = Flux.error<AggregateId>(RuntimeException("error"))
        val afterId = "id0"

        // Act
        flux.toBatchResult(afterId)
            .test()
            .expectNext(BatchResult(afterId, 0, ErrorCodes.BAD_REQUEST, "error"))
            .verifyComplete()
    }
}
