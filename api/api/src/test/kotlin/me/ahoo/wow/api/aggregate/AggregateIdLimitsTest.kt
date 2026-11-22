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

package me.ahoo.wow.api.aggregate

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class AggregateIdLimitsTest {
    @Test
    fun `the id's share of the path should be what is left of it, over the most an encoder writes for a byte`() {
        AggregateIdLimits.MAX_LENGTH.assert().isEqualTo(256)
        AggregateIdLimits.MAX_BYTES.assert().isEqualTo(512)
        // The longest id percent-encoded and the longest rest of a route fill the path, and no more.
        (AggregateIdLimits.MAX_BYTES * AggregateIdLimits.MAX_ESCAPE + AggregateIdLimits.MAX_ROUTE_REST).assert()
            .isLessThanOrEqualTo(AggregateIdLimits.MAX_PATH_LENGTH)
    }

    @Test
    fun `the length in UTF-8 should count each code point's bytes, a lone surrogate the most an encoder writes`() {
        AggregateIdLimits.utf8Length("").assert().isZero()
        AggregateIdLimits.utf8Length("aZ09-._~ %").assert().isEqualTo(10)
        AggregateIdLimits.utf8Length("é").assert().isEqualTo(2)
        AggregateIdLimits.utf8Length("߿").assert().isEqualTo(2)
        AggregateIdLimits.utf8Length("ࠀ").assert().isEqualTo(3)
        AggregateIdLimits.utf8Length("购").assert().isEqualTo(3)
        AggregateIdLimits.utf8Length("￿").assert().isEqualTo(3)
        AggregateIdLimits.utf8Length("😀").assert().isEqualTo(4)
        AggregateIdLimits.utf8Length("\uD83D").assert().isEqualTo(3)
        AggregateIdLimits.utf8Length("\uDE00x").assert().isEqualTo(4)
        listOf("a", "é", "购", "😀", "x😀购é").forEach {
            AggregateIdLimits.utf8Length(it).assert().isEqualTo(it.toByteArray(Charsets.UTF_8).size)
        }
    }

    @Test
    fun `the encoded length should keep slashes and unreserved characters, and escape each other byte`() {
        AggregateIdLimits.encodedLength("/aZ09-._~").assert().isEqualTo(9)
        // One byte each: a space, '%', '?', '#', '+', '*', '{', '}'.
        AggregateIdLimits.encodedLength(" %?#+*{}").assert().isEqualTo(24)
        // Two bytes (é), three (购), four (a surrogate pair, 😀).
        AggregateIdLimits.encodedLength("é").assert().isEqualTo(6)
        AggregateIdLimits.encodedLength("购").assert().isEqualTo(9)
        AggregateIdLimits.encodedLength("😀").assert().isEqualTo(12)
        AggregateIdLimits.encodedLength("/订单/order").assert().isEqualTo(1 + 18 + 1 + 5)
    }
}
