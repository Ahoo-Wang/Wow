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

package me.ahoo.wow.query.filter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class QueryTypeTest {

    @Test
    fun `single should not be dynamic`() {
        QueryType.SINGLE.isDynamic.assert().isFalse()
    }

    @Test
    fun `dynamic single should be dynamic`() {
        QueryType.DYNAMIC_SINGLE.isDynamic.assert().isTrue()
    }

    @Test
    fun `list should not be dynamic`() {
        QueryType.LIST.isDynamic.assert().isFalse()
    }

    @Test
    fun `dynamic list should be dynamic`() {
        QueryType.DYNAMIC_LIST.isDynamic.assert().isTrue()
    }

    @Test
    fun `paged should not be dynamic`() {
        QueryType.PAGED.isDynamic.assert().isFalse()
    }

    @Test
    fun `dynamic paged should be dynamic`() {
        QueryType.DYNAMIC_PAGED.isDynamic.assert().isTrue()
    }

    @Test
    fun `count should not be dynamic`() {
        QueryType.COUNT.isDynamic.assert().isFalse()
    }
}
