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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * #6594: the aggregate ids no route by their id reaches behind a gateway's path check (CoSec 8): one holding `/`, `;`
 * or `\`, and one longer than 256 characters percent-encoded as UTF-8.
 */
class AggregateIdsTest {
    @Test
    fun `an id holding a slash, a semicolon or a backslash should be unaddressable, saying which it holds`() {
        mapOf("a/b" to '/', "a;b" to ';', "a\\b" to '\\', "/" to '/', ";x" to ';').forEach { (id, held) ->
            AggregateIds.unaddressable(id).assert().isEqualTo(
                "The aggregate id '$id' holds '$held', which a gateway's path check refuses in a path, so no route " +
                    "by its id would reach it: an aggregate id holds no '/', ';' or '\\'.",
            )
        }
    }

    @Test
    fun `an id longer than 256 characters percent-encoded should be unaddressable, however few its characters`() {
        // 29 characters of three UTF-8 bytes: 261 encoded, refused; 28 of them, 252, taken.
        AggregateIds.unaddressable("购".repeat(29)).assert().isEqualTo(
            "The aggregate id is 261 characters percent-encoded as UTF-8, which a gateway's path check refuses past " +
                "256: a shorter id, or one of letters, digits, '-', '.', '_' and '~', fits.",
        )
        AggregateIds.unaddressable("购".repeat(28)).assert().isNull()
        // Unreserved characters count one each: 256 of them fit, as does one of each kind.
        AggregateIds.unaddressable("x".repeat(256)).assert().isNull()
        AggregateIds.unaddressable("a" + "~".repeat(254) + "%").assert().isNotNull()
        AggregateIds.unaddressable("aZ09-._~").assert().isNull()
    }

    @Test
    fun `the encoded length should count each unreserved character once and each other UTF-8 byte three times`() {
        AggregateIds.encodedLength("aZ09-._~").assert().isEqualTo(8)
        // One byte each: a space, '%', '?', '#', '+', '*'.
        AggregateIds.encodedLength(" %?#+*").assert().isEqualTo(18)
        // Two bytes (é), three (购), four (a surrogate pair, 😀).
        AggregateIds.encodedLength("é").assert().isEqualTo(6)
        AggregateIds.encodedLength("购").assert().isEqualTo(9)
        AggregateIds.encodedLength("😀").assert().isEqualTo(12)
        // A lone surrogate is written as the replacement character, three bytes.
        AggregateIds.encodedLength("\uD83D").assert().isEqualTo(9)
    }

    @Test
    fun `refused should take an id's length first, then what makes it unaddressable, and every other id`() {
        AggregateIds.refused("x".repeat(257)).assert()
            .isEqualTo("The aggregate id is at most 256 characters; this one has 257.")
        AggregateIds.refused("a/b").assert().isEqualTo(AggregateIds.unaddressable("a/b"))
        listOf("c-1", "a.b", "...", "a b", "a%b", "q?m", "h#t", "p+s", "é", "购").forEach {
            AggregateIds.refused(it).assert().isNull()
        }
        AggregateIds.refused(null).assert().isNull()
        AggregateIds.unaddressable(" ").assert().isNull()
    }
}
