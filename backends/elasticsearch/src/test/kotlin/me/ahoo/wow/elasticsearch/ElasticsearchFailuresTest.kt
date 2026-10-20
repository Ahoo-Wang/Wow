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

package me.ahoo.wow.elasticsearch

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test

class ElasticsearchFailuresTest {
    @Test
    fun `a bulk item's failure is transient, a refused value, or a defect by its status`() {
        ElasticsearchFailures.itemFailure(429, "the document [a]", "rejected")
            .assert().isInstanceOf(StorageUnavailableException::class.java)
        ElasticsearchFailures.itemFailure(503, "the document [a]", "unavailable_shards_exception")
            .assert().isInstanceOf(StorageUnavailableException::class.java)
        val refused = ElasticsearchFailures.itemFailure(400, "the document [a]", "mapper_parsing_exception")
        refused.assert().isInstanceOf(IllegalArgumentException::class.java)
        refused.message.assert().isEqualTo("Elasticsearch answered 400 for the document [a]: mapper_parsing_exception")
        ElasticsearchFailures.itemFailure(500, "the document [a]", null)
            .assert().isInstanceOf(IllegalStateException::class.java)
    }
}
