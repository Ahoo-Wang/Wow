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
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference

/**
 * The query bodies: `filter` is required and never null, `condition` (9.x's legacy filter) is an unknown property like
 * any other, and the V10 count and total additions.
 */
class QueryRequestJsonTest {
    private val mapper = QueryJson.mapper
    private val queryTypes = listOf(ListQuery::class.java, PagedQuery::class.java, SingleQuery::class.java)

    @Test
    fun `query bodies should require a filter`() {
        listOf("{}", """{"filter":null}""", """{"condition":{"field":"state.status","operator":"EQ","value":"PAID"}}""")
            .forEach { body ->
                (queryTypes + CursorQuery::class.java + CountQuery::class.java).forEach { type ->
                    assertThrows<JacksonException> { mapper.readValue(body, type) }
                }
            }
    }

    @Test
    fun `query bodies should ignore condition as an unknown property`() {
        listOf(
            """{"filter":{"op":"MATCH_ALL"},"condition":{"operator":"ALL"}}""",
            """{"filter":{"op":"MATCH_ALL"},"condition":null}""",
            """{"filter":{"op":"MATCH_ALL"},"condition":{"op":"MATCH_ALL","operator":"ALL"}}""",
        ).forEach { body ->
            mapper.readValue(body, ListQuery::class.java).assert().isEqualTo(ListQuery(MatchAllFilter))
            mapper.readValue(body, PagedQuery::class.java).assert().isEqualTo(PagedQuery(MatchAllFilter))
            mapper.readValue(body, SingleQuery::class.java).assert().isEqualTo(SingleQuery(MatchAllFilter))
        }
    }

    @Test
    fun `a query body that is not an object should be rejected`() {
        listOf("[]", "\"filter\"", "1", "true").forEach { body ->
            queryTypes.forEach { type ->
                assertThrows<JacksonException> { mapper.readValue(body, type) }
            }
        }
    }

    @Test
    fun `query bodies should take the constructor defaults`() {
        val filter = """{"filter":{"op":"TENANT_ID","value":"tenant-1"}}"""
        val tenant = TenantIdFilter("tenant-1")
        mapper.readValue(filter, ListQuery::class.java).assert().isEqualTo(ListQuery(tenant))
        mapper.readValue(filter, PagedQuery::class.java).assert().isEqualTo(
            PagedQuery(tenant, Projection.ALL, emptyList(), Pagination.DEFAULT, TotalMode.BOUNDED),
        )
        mapper.readValue(filter, SingleQuery::class.java).assert().isEqualTo(SingleQuery(tenant))
        mapper.readValue(filter, CursorQuery::class.java).assert().isEqualTo(CursorQuery(tenant))
        mapper.readValue(filter, CountQuery::class.java).assert().isEqualTo(CountQuery(tenant))
    }

    @Test
    fun `paged query total should read its upper-case names`() {
        TotalMode.entries.forEach { mode ->
            mapper.readValue("""{"filter":{"op":"MATCH_ALL"},"total":"${mode.name}"}""", PagedQuery::class.java)
                .total.assert().isEqualTo(mode)
        }
        assertThrows<JacksonException> {
            mapper.readValue("""{"filter":{"op":"MATCH_ALL"},"total":"exact"}""", PagedQuery::class.java)
        }
    }

    @Test
    fun `count query limit may be absent or null but otherwise at least one`() {
        mapper.readValue("""{"filter":{"op":"MATCH_ALL"},"limit":null}""", CountQuery::class.java)
            .assert().isEqualTo(CountQuery(MatchAllFilter))
        mapper.readValue("""{"filter":{"op":"MATCH_ALL"},"limit":1}""", CountQuery::class.java)
            .assert().isEqualTo(CountQuery(MatchAllFilter, 1))
        assertThrows<JacksonException> {
            mapper.readValue("""{"filter":{"op":"MATCH_ALL"},"limit":0}""", CountQuery::class.java)
        }
    }

    @Test
    fun `count result should be validated on read`() {
        mapper.readValue("""{"count":3,"exact":true}""", CountResult::class.java).assert()
            .isEqualTo(CountResult(3, true))
        assertThrows<JacksonException> { mapper.readValue("""{"count":-1,"exact":true}""", CountResult::class.java) }
    }

    @Test
    fun `paged list should read an uncounted total and default exact to its presence`() {
        val type = object : TypeReference<PagedList<String>>() {}
        mapper.readValue("""{"total":null,"exact":false,"list":["a"]}""", type)
            .assert().isEqualTo(PagedList(null, listOf("a")))
        mapper.readValue("""{"total":2,"list":["a","b"]}""", type)
            .assert().isEqualTo(PagedList(2, listOf("a", "b"), exact = true))
        assertThrows<JacksonException> { mapper.readValue("""{"total":null,"exact":true,"list":[]}""", type) }
    }
}
