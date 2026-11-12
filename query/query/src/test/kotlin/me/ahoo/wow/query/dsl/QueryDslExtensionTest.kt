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
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.dsl.ListQueryDsl
import me.ahoo.wow.api.query.dsl.listQuery
import org.junit.jupiter.api.Test

/**
 * An application's own step on a DSL class, the one thing users do with the classes themselves. It lives outside
 * `:query-api`, so it compiles against the public surface only: the verbs and the class as a block's receiver.
 */
private fun ListQueryDsl.paidByName() {
    filter { "state.status" eq "PAID" }
    sort { "state.name".asc() }
    limit(20)
}

class QueryDslExtensionTest {
    @Test
    fun `an extension function on a DSL class works inside its block`() {
        listQuery { paidByName() }.assert().isEqualTo(
            ListQuery(
                EqualFilter(QueryField("state.status"), "PAID"),
                sort = listOf(Sort(QueryField("state.name"), Sort.Direction.ASC)),
                limit = 20,
            ),
        )
    }
}
