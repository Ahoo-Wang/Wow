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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test

class CursorQueryDslTest {
    @Test
    fun `cursor query dsl should build cursor query`() {
        val query = cursorQuery {
            size(20)
            cursor("next")
            sort { "state.createdAt".desc() }
        }

        query.filter.assert().isEqualTo(MatchAllFilter)
        query.sort.assert().isEqualTo(listOf(Sort("state.createdAt", Sort.Direction.DESC)))
        query.size.assert().isEqualTo(20)
        query.cursor.assert().isEqualTo("next")
    }

    @Test
    fun `cursor query dsl should use cursor defaults`() {
        val query = cursorQuery { }

        query.filter.assert().isEqualTo(MatchAllFilter)
        query.size.assert().isEqualTo(CursorQuery.DEFAULT_SIZE)
        query.cursor.assert().isNull()
    }
}
