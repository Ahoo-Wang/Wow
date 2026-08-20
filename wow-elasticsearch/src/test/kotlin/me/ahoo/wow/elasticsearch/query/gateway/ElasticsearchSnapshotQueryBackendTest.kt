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

package me.ahoo.wow.elasticsearch.query.gateway

import co.elastic.clients.elasticsearch.core.CountResponse
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.query.QueryException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchSnapshotQueryBackendTest {
    @Test
    fun `should reject partial count results`() {
        val response = CountResponse.of { count ->
            count.count(42).shards { shards -> shards.total(2).successful(1).failed(1) }
        }

        assertThrows<QueryException> { exactCount(response) }
            .code.assert().isEqualTo(QueryErrorCode.BACKEND_FAILURE)
    }

    @Test
    fun `should classify mapping transport errors as backend failures`() {
        val mapped = mapMappingError(IllegalStateException("connection failed")) as QueryException

        mapped.code.assert().isEqualTo(QueryErrorCode.BACKEND_FAILURE)
    }

    @Test
    fun `should saturate a maximum record budget probe`() {
        Long.MAX_VALUE.incrementSaturated().assert().isEqualTo(Long.MAX_VALUE)
        41L.incrementSaturated().assert().isEqualTo(42L)
    }
}
