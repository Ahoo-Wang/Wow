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
 * or `\`, one longer than 256 characters (UTF-16 units), and one longer than 512 bytes in UTF-8.
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
    fun `an id of more than 512 bytes in UTF-8 should be unaddressable, saying its characters and its bytes`() {
        // 171 characters of three bytes: 513, refused; 170 of them, 510, taken.
        AggregateIds.unaddressable("购".repeat(171)).assert().isEqualTo(
            "The aggregate id has 171 characters, 513 bytes in UTF-8, more than the 512 bytes a route by it carries " +
                "behind a gateway: 512 bytes are 256 characters of most scripts and 170 of Chinese, Japanese or Korean.",
        )
        AggregateIds.unaddressable("购".repeat(170)).assert().isNull()
        AggregateIds.unaddressable("购".repeat(170) + "xx").assert().isNull()
        AggregateIds.unaddressable("购".repeat(170) + "xxx").assert().isNotNull()
        // Characters of one or two bytes fit to the length: 256 of them.
        AggregateIds.refused("x".repeat(256)).assert().isNull()
        AggregateIds.refused("é".repeat(256)).assert().isNull()
        // Characters of four bytes, two UTF-16 units each: 128 of them fit.
        AggregateIds.refused("😀".repeat(128)).assert().isNull()
        AggregateIds.unaddressable("😀".repeat(128) + "x").assert().isNotNull()
        // A lone surrogate counts three bytes, the most an encoder writes for it.
        AggregateIds.unaddressable("\uD83D".repeat(170) + "xx").assert().isNull()
        AggregateIds.unaddressable("\uD83D".repeat(170) + "xxx").assert().isNotNull()
        // Percent-encoding counts nothing: characters a path escapes take their bytes only.
        AggregateIds.refused("a %?#+*".repeat(36)).assert().isNull()
    }

    @Test
    fun `refused should take an id's length first, then what makes it unaddressable, and every other id`() {
        AggregateIds.refused("x".repeat(257)).assert()
            .isEqualTo("The aggregate id is at most 256 characters; this one has 257.")
        // 128 characters of two UTF-16 units each, and one more: refused by its length, not its bytes.
        AggregateIds.refused("😀".repeat(128) + "x").assert()
            .isEqualTo("The aggregate id is at most 256 characters; this one has 257.")
        AggregateIds.refused("购".repeat(257)).assert()
            .isEqualTo("The aggregate id is at most 256 characters; this one has 257.")
        AggregateIds.refused("购".repeat(171)).assert().isEqualTo(AggregateIds.unaddressable("购".repeat(171)))
        AggregateIds.refused("a/b").assert().isEqualTo(AggregateIds.unaddressable("a/b"))
        listOf("c-1", "a.b", "...", "a b", "a%b", "q?m", "h#t", "p+s", "é", "购").forEach {
            AggregateIds.refused(it).assert().isNull()
        }
        AggregateIds.refused(null).assert().isNull()
        AggregateIds.unaddressable(" ").assert().isNull()
    }

    @Test
    fun `the limits should be those of the gateway and of the path a route by id leaves its id`() {
        AggregateIds.MAX_LENGTH.assert().isEqualTo(256)
        AggregateIds.MAX_BYTES.assert().isEqualTo(512)
    }
}
