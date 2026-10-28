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

package me.ahoo.wow.http.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryRequestException
import org.junit.jupiter.api.Test

/** How a query route reads its body: the kind, the query, and the binding error of a body it cannot read. */
class QueryBodiesTest {
    private fun refused(body: String?): QueryRequestException =
        runCatching { QueryBodies.read(body?.toByteArray()) }.exceptionOrNull() as QueryRequestException

    private fun assertRefused(body: String?, code: String, name: String = QueryRequestException.BODY) {
        val error = refused(body)
        error.errorCode.assert().isEqualTo(QueryErrors.MALFORMED)
        error.bindingErrors.single().let {
            it.code.assert().isEqualTo(code)
            it.name.assert().isEqualTo(name)
        }
    }

    @Test
    fun `a body should read as the query its kind names`() {
        QueryBodies.read("""{"kind":"list","filter":{"op":"match-all"},"limit":3}""".toByteArray()).let {
            it.kind.assert().isEqualTo(QueryKind.LIST)
            (it.query as ListQuery).limit.assert().isEqualTo(3)
        }
        QueryBodies.read("""{"kind":"count","filter":{"op":"match-all"},"limit":5}""".toByteArray())
            .query.assert().isEqualTo(CountQuery(me.ahoo.wow.api.query.MatchAllFilter, 5))
        QueryKind.of("nope").assert().isNull()
    }

    @Test
    fun `a body that cannot be read should name where and why`() {
        assertRefused("  \n ", QueryErrorCodes.EMPTY_BODY)
        assertRefused(
            """{"kind":"list","filter":{"op":"match-all"},"limit":"many"}""",
            QueryErrorCodes.INVALID_VALUE,
            "limit"
        )
        assertRefused(
            """{"kind":"list","filter":{"op":"and","operands":[{"op":"match-all"},{"op":"NOPE"}]}}""",
            QueryErrorCodes.UNKNOWN_TYPE,
            "filter.operands[1]",
        )
        assertRefused(
            """{"kind":"list","filter":{"op":"match-all"},"sort":[{"field":"version","direction":"UP"}]}""",
            QueryErrorCodes.UNKNOWN_VALUE,
            "sort[0].direction",
        )
        assertRefused("""{"kind":"cursor","filter":{"op":"match-all"},"size":0}""", QueryErrorCodes.INVALID_REQUEST)
        assertRefused("""{"kind":"list","filter":"MATCH_ALL"}""", QueryErrorCodes.INVALID_VALUE, "filter")
        refused("{\"kind\":").message.assert().contains("line 1")
        // A number the limit cannot hold, and a filter that names no operator.
        assertRefused(
            """{"kind":"list","filter":{"op":"match-all"},"limit":99999999999}""",
            QueryErrorCodes.INVALID_REQUEST,
            "limit",
        )
        refused("""{"kind":"list","filter":{"field":"version","value":1}}""").errorCode.assert()
            .isEqualTo(QueryErrors.MALFORMED)
    }
}
