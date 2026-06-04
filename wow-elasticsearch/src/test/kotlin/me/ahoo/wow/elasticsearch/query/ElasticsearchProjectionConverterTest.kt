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

package me.ahoo.wow.elasticsearch.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.query.ElasticsearchProjectionConverter.toSourceFilter
import me.ahoo.wow.query.dsl.projection
import org.junit.jupiter.api.Test

class ElasticsearchProjectionConverterTest {
    @Test
    fun `should convert projection to SourceFilter`() {
        val projection = projection {
            include("field1")
            exclude("field2")
        }

        val sourceFilter = projection.toSourceFilter()
        sourceFilter.includes().assert().contains("field1")
        sourceFilter.excludes().assert().contains("field2")
    }
}
