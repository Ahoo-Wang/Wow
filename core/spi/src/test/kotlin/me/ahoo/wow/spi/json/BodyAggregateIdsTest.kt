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

package me.ahoo.wow.spi.json

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** The aggregate id a command's JSON body holds in the property its contract names. */
class BodyAggregateIdsTest {
    private fun body(json: String) = WowJson.mapper.readTree(json)

    @Test
    fun `the property's string is the id, and a body that leaves it out or null names none`() {
        BodyAggregateIds.of(body("""{"productId":"mug","quantity":1}"""), "productId").assert().isEqualTo("mug")
        BodyAggregateIds.of(body("""{"quantity":1}"""), "productId").assert().isNull()
        BodyAggregateIds.of(body("""{"productId":null}"""), "productId").assert().isNull()
    }

    @Test
    fun `a property that holds anything but a string is no id`() {
        assertThrownBy<IllegalArgumentException> { BodyAggregateIds.of(body("""{"productId":7}"""), "productId") }
            .hasMessage("The body's productId, the id of its aggregate, is no string: 7.")
    }
}
