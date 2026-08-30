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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper

class CursorPageTest {
    private val mapper = jsonMapper()

    @Test
    fun `should round trip cursor page`() {
        val json = mapper.writeValueAsString(CursorPage(listOf("one"), "next"))
        val page = mapper.readValue(json, CursorPage::class.java)

        page.list.assert().containsExactly("one")
        page.nextCursor.assert().isEqualTo("next")
    }
}
