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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class QueryFieldNamesTest {
    @Test
    fun `names are upper snake case`() {
        QueryFieldNames.upperSnake("productId").assert().isEqualTo("PRODUCT_ID")
        QueryFieldNames.upperSnake("URLValue").assert().isEqualTo("URL_VALUE")
        QueryFieldNames.upperSnake("first-name").assert().isEqualTo("FIRST_NAME")
    }

    @Test
    fun `a path's constant joins its segments with double underscores`() {
        QueryFieldNames.constantOf("state.items.productId").assert().isEqualTo("STATE__ITEMS__PRODUCT_ID")
        QueryFieldNames.constantOf(QueryFieldNames.STATE).assert().isEqualTo("STATE")
    }

    @Test
    fun `a segment is an identifier that may hold dashes`() {
        QueryFieldNames.isSegment("first-name").assert().isTrue()
        QueryFieldNames.isSegment("_id").assert().isTrue()
        QueryFieldNames.isSegment("1st").assert().isFalse()
        QueryFieldNames.isSegment("a.b").assert().isFalse()
        QueryFieldNames.isSegment("").assert().isFalse()
    }
}
