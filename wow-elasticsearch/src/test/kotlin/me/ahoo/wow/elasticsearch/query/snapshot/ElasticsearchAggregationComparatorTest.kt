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

package me.ahoo.wow.elasticsearch.query.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchAggregationComparatorTest {
    @Test
    fun `should preserve portable null numeric and UTF-8 order`() {
        compareAggregationValues(9_007_199_254_740_992L, 9_007_199_254_740_993L, Sort.Direction.ASC)
            .assert().isLessThan(0)
        compareAggregationValues("\uD800\uDC00", "\uE000", Sort.Direction.ASC).assert().isGreaterThan(0)
        compareAggregationValues(null, 1L, Sort.Direction.ASC).assert().isLessThan(0)
        compareAggregationValues(1L, null, Sort.Direction.DESC).assert().isLessThan(0)
        compareAggregationValues(1L, 1.0, Sort.Direction.ASC).assert().isZero()
        assertThrows<IllegalStateException> {
            compareAggregationValues(true, "true", Sort.Direction.ASC)
        }
    }
}
