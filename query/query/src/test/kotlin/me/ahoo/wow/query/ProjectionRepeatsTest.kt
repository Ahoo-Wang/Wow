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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import org.junit.jupiter.api.Test

/**
 * A projection's repeated entry asks nothing more and is kept once at admission, so no backend copies a record's
 * subtree once per repetition (#7074): a 256 KiB body holds some 30,000 repetitions of one include.
 */
class ProjectionRepeatsTest {
    private val items = QueryField("state.items")
    private val note = QueryField("state.note")
    private val schema = gatewaySchema(
        fields = mapOf(
            items to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
            note to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
        ),
    )

    private fun admitted(projection: Projection): Projection {
        val query = ListQuery(MatchAllFilter, projection = projection, limit = 10)
        return QueryAdmission.Trusted.list(query, schema).query.projection
    }

    @Test
    fun `a projection that repeats an entry ten thousand times is admitted with it once`() {
        admitted(Projection(include = List(10_000) { items } + note)).include.map { it.path }.assert()
            .containsExactly("state.items", "state.note")
        admitted(Projection(exclude = List(3) { note })).exclude.map { it.path }.assert().containsExactly("state.note")
    }
}
