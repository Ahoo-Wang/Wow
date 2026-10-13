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

package me.ahoo.wow.query.json

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException

/** The JSON halves of 9.x `CursorPageTest` and `QueryFieldTest`. */
class QueryResultJsonTest {
    private val mapper = QueryJson.mapper

    @Test
    fun `should round trip cursor page`() {
        val json = mapper.writeValueAsString(CursorPage(listOf("one"), "next"))
        val page = mapper.readValue(json, CursorPage::class.java)

        json.assert().isEqualTo("""{"list":["one"],"nextCursor":"next"}""")
        page.list.assert().containsExactly("one")
        page.nextCursor.assert().isEqualTo("next")
    }

    @Test
    fun `should round trip terminal cursor page`() {
        val json = mapper.writeValueAsString(CursorPage(emptyList<String>(), null))
        val page = mapper.readValue(json, CursorPage::class.java)

        json.assert().isEqualTo("""{"list":[],"nextCursor":null}""")
        page.list.assert().isEmpty()
        page.nextCursor.assert().isNull()
    }

    @Test
    fun `query field should serialize as a string and reject backend patterns`() {
        val field = QueryField("state.name")
        mapper.writeValueAsString(field).assert().isEqualTo("\"state.name\"")
        mapper.readValue("\"state.name\"", QueryField::class.java).assert().isEqualTo(field)
        assertThrows<JacksonException> { mapper.readValue("\"field.*\"", QueryField::class.java) }
        assertThrows<JacksonException> { mapper.readValue("""{"path":"state.name"}""", QueryField::class.java) }
    }
}
