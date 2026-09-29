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

package me.ahoo.wow.viewstore.api

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ScopeIdsTest {
    @Test
    fun `an id is what it displays as`() {
        listOf("alice", "t1", "(shared)", "(0)", "张三", "alice-1_x.y@z", "😀").forEach {
            ScopeIds.isValid(it).assert().describedAs(it).isTrue()
        }
    }

    @Test
    fun `nothing invisible or blank`() {
        listOf(
            null, "", " ", "\t", "　", " ", " ", "\u0085", "\u0000", "alice ", "al ice",
            // Format characters: zero-width space, byte order mark, soft hyphen, word joiner, Mongolian vowel separator.
            "alice​", "﻿alice", "al­ice", "alice⁠", "alice᠎",
            "alice\uD800", "", "alice͸",
        ).forEach {
            ScopeIds.isValid(it).assert().describedAs(it.toString()).isFalse()
        }
    }
}
