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

package me.ahoo.wow.webflux.exception

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class BatchTaskExceptionTest {

    @Test
    fun `should create batch task exception with aggregate id`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("test-id")
        val cause = RuntimeException("cause")
        val exception = BatchTaskException(aggregateId, cause)

        exception.aggregateId.assert().isEqualTo(aggregateId)
        exception.errorCode.assert().isEqualTo(BatchTaskException.ERROR_CODE)
        exception.cause.assert().isEqualTo(cause)
    }

    @Test
    fun `should create batch task exception without cause`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("test-id")
        val exception = BatchTaskException(aggregateId)

        exception.aggregateId.assert().isEqualTo(aggregateId)
        exception.cause.assert().isNull()
    }

    @Test
    fun `should map mono error to batch task exception`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("test-id")

        RuntimeException("error")
            .toMono<String>()
            .onErrorMapBatchTaskException(aggregateId)
            .test()
            .expectErrorMatches {
                it is BatchTaskException && it.aggregateId == aggregateId
            }
            .verify()
    }
}
